/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.exchange;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.operator.OperatorInfo;
import io.trino.spi.exchange.ExchangeSource;
import io.trino.spi.exchange.ExchangeSourceHandle;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.airlift.concurrent.MoreFutures.toListenableFuture;
import static java.util.Objects.requireNonNull;

public class SpoolingExchangeDataSource
        implements ExchangeDataSource
{
    private static final Logger log = Logger.get(SpoolingExchangeDataSource.class);

    // This field is not final to allow releasing the memory retained by the ExchangeSource instance.
    // It is modified (assigned to null) when the ExchangeOperator is closed.
    // It doesn't have to be declared as volatile as the nullification of this variable doesn't have to be immediately visible to other threads.
    // However since close can be called at any moment this variable has to be accessed in a safe way (avoiding "check-then-use").
    private ExchangeSource exchangeSource;
    private final List<ExchangeSourceHandle> exchangeSourceHandles;
    private final LocalMemoryContext systemMemoryContext;
    private volatile boolean closed;

    public SpoolingExchangeDataSource(
            ExchangeSource exchangeSource,
            List<ExchangeSourceHandle> exchangeSourceHandles,
            LocalMemoryContext systemMemoryContext)
    {
        // this assignment is expected to be followed by an assignment of a final field to ensure safe publication
        this.exchangeSource = requireNonNull(exchangeSource, "exchangeSource is null");
        this.exchangeSourceHandles = ImmutableList.copyOf(requireNonNull(exchangeSourceHandles, "exchangeSourceHandles is null"));
        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }

    @Override
    public Slice pollPage()
    {
        ExchangeSource exchangeSource = this.exchangeSource;
        if (exchangeSource == null) {
            return null;
        }

        Slice data = exchangeSource.read();
        systemMemoryContext.setBytes(exchangeSource.getMemoryUsage());

        // If the data source has been closed in a meantime reset memory usage back to 0
        if (closed) {
            systemMemoryContext.setBytes(0);
        }

        return data;
    }

    @Override
    public boolean isFinished()
    {
        ExchangeSource exchangeSource = this.exchangeSource;
        if (exchangeSource == null) {
            return true;
        }
        return exchangeSource.isFinished();
    }

    @Override
    public ListenableFuture<Void> isBlocked()
    {
        ExchangeSource exchangeSource = this.exchangeSource;
        if (exchangeSource == null) {
            return immediateVoidFuture();
        }
        return toListenableFuture(exchangeSource.isBlocked());
    }

    @Override
    public void addInput(ExchangeInput input)
    {
        SpoolingExchangeInput exchangeInput = (SpoolingExchangeInput) input;
        // Only a single input is expected when the spooling exchange is used.
        // The engine adds the same input to every instance of the ExchangeOperator.
        // Since the ExchangeDataSource is shared between ExchangeOperator instances
        // the same input may be delivered multiple times.
        checkState(
                exchangeInput.getExchangeSourceHandles().equals(exchangeSourceHandles),
                "exchange input is expected to contain an identical exchangeSourceHandles list: %s != %s",
                exchangeInput.getExchangeSourceHandles(),
                exchangeSourceHandles);
    }

    @Override
    public void noMoreInputs()
    {
        // Only a single input is expected when the spooling exchange is used.
        // Thus the assumption of "noMoreSplit" is made on construction.
    }

    @Override
    public OperatorInfo getInfo()
    {
        return null;
    }

    @Override
    public synchronized void close()
    {
        if (closed) {
            return;
        }
        closed = true;
        try {
            exchangeSource.close();
        }
        catch (RuntimeException e) {
            log.warn(e, "error closing exchange source");
        }
        finally {
            exchangeSource = null;
            systemMemoryContext.setBytes(0);
        }
    }
}
