package net.openhft.samples.microservices.orders;

import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.MessageHistory;
import net.openhft.chronicle.wire.MethodReader;
import org.junit.Test;

import java.io.File;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

/**
 * Created by peter on 24/03/16.
 */
public class OrderManagerTest {

    @Test
    public void testOnOrderIdea() {
// what we expect to happen
        OrderListener listener = createMock(OrderListener.class);
        listener.onOrder(new Order("EURUSD", Side.Buy, 1.1167, 1_000_000));
        replay(listener);

// build our scenario
        OrderManager orderManager = new OrderManager(listener);
        SidedMarketDataCombiner combiner = new SidedMarketDataCombiner(orderManager);

// events in
        orderManager.onOrderIdea(new OrderIdea("strategy1", "EURUSD", Side.Buy, 1.1180, 2e6)); // not expected to trigger

        combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789000L, Side.Sell, 1.1172, 2e6));
        combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789100L, Side.Buy, 1.1160, 2e6));

        combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789100L, Side.Buy, 1.1167, 2e6));

        orderManager.onOrderIdea(new OrderIdea("strategy1", "EURUSD", Side.Buy, 1.1165, 1e6)); // expected to trigger

        verify(listener);
    }

    @Test
    public void testWithQueue() {
        File queuePath = new File(OS.TARGET, "testWithQueue-" + System.nanoTime());
        try {
            try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(queuePath).build()) {
                OrderIdeaListener orderManager = queue.acquireAppender().methodWriter(OrderIdeaListener.class, MarketDataListener.class);
                SidedMarketDataCombiner combiner = new SidedMarketDataCombiner((MarketDataListener) orderManager);

                // events in
                orderManager.onOrderIdea(new OrderIdea("strategy1", "EURUSD", Side.Buy, 1.1180, 2e6)); // not expected to trigger

                combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789000L, Side.Sell, 1.1172, 2e6));
                combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789100L, Side.Buy, 1.1160, 2e6));

                combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789100L, Side.Buy, 1.1167, 2e6));

                orderManager.onOrderIdea(new OrderIdea("strategy1", "EURUSD", Side.Buy, 1.1165, 1e6)); // expected to trigger
            }

// what we expect to happen
            OrderListener listener = createMock(OrderListener.class);
            listener.onOrder(new Order("EURUSD", Side.Buy, 1.1167, 1_000_000));
            replay(listener);

            try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(queuePath).build()) {
                // build our scenario
                OrderManager orderManager = new OrderManager(listener);
                MethodReader reader = queue.createTailer().methodReader(orderManager);
                for (int i = 0; i < 5; i++)
                    assertTrue(reader.readOne());

                assertFalse(reader.readOne());
                System.out.println(queue.dump());
            }

            verify(listener);
        } finally {
            try {
                IOTools.shallowDeleteDirWithFiles(queuePath);
            } catch (Exception e) {

            }
        }
    }

    @Test
    public void testWithQueueHistory() {
        File queuePath = new File(OS.TARGET, "testWithQueueHistory-" + System.nanoTime());
        File queuePath2 = new File(OS.TARGET, "testWithQueueHistory-down-" + System.nanoTime());
        try {
            try (SingleChronicleQueue out = SingleChronicleQueueBuilder.binary(queuePath).build()) {
                OrderIdeaListener orderManager = out.acquireAppender()
                        .methodWriterBuilder(OrderIdeaListener.class)
                        .addInterface(MarketDataListener.class)
                        .recordHistory(true)
                        .get();
                SidedMarketDataCombiner combiner = new SidedMarketDataCombiner((MarketDataListener) orderManager);

                // events in
                orderManager.onOrderIdea(new OrderIdea("strategy1", "EURUSD", Side.Buy, 1.1180, 2e6)); // not expected to trigger

                combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789000L, Side.Sell, 1.1172, 2e6));
                combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789100L, Side.Buy, 1.1160, 2e6));

                combiner.onSidedPrice(new SidedPrice("EURUSD", 123456789100L, Side.Buy, 1.1167, 2e6));

                orderManager.onOrderIdea(new OrderIdea("strategy1", "EURUSD", Side.Buy, 1.1165, 1e6)); // expected to trigger
            }

            try (SingleChronicleQueue in = SingleChronicleQueueBuilder.binary(queuePath)
                    .sourceId(1)
                    .build();
                 SingleChronicleQueue out = SingleChronicleQueueBuilder.binary(queuePath2).build()) {

                OrderListener listener = out.acquireAppender()
                        .methodWriterBuilder(OrderListener.class)
                        .recordHistory(true)
                        .get();
                // build our scenario
                OrderManager orderManager = new OrderManager(listener);
                MethodReader reader = in.createTailer().methodReader(orderManager);
                for (int i = 0; i < 5; i++)
                    assertTrue(reader.readOne());

                assertFalse(reader.readOne());
                System.out.println(out.dump());
            }

            try (SingleChronicleQueue in = SingleChronicleQueueBuilder.binary(queuePath2).sourceId(2).build()) {
                MethodReader reader = in.createTailer().methodReader((OrderListener) order -> {
                    MessageHistory x = MessageHistory.get();
                    // Note: this will have one extra timing, the time it was written to the console.
                    System.out.println(x);
                    assertEquals(1, x.sourceId(0));
                    assertEquals(2, x.sourceId(1));
                    assertEquals(4, x.timings());
                });
                assertTrue(reader.readOne());
                assertFalse(reader.readOne());
            }
        } finally {
            try {
                IOTools.shallowDeleteDirWithFiles(queuePath);
                IOTools.shallowDeleteDirWithFiles(queuePath2);
            } catch (Exception e) {

            }
        }
    }

    @Test
    public void testRestartingAService() {
        File queuePath = new File(OS.TARGET, "testRestartingAService-" + System.nanoTime());
        File queuePath2 = new File(OS.TARGET, "testRestartingAService-down-" + System.nanoTime());
        try {
            try (SingleChronicleQueue out = SingleChronicleQueueBuilder.binary(queuePath)
                    .rollCycle(RollCycles.TEST_DAILY)
                    .build()) {
                SidedMarketDataListener combiner = out.acquireAppender()
                        .methodWriterBuilder(SidedMarketDataListener.class)
                        .recordHistory(true)
                        .get();

                combiner.onSidedPrice(new SidedPrice("EURUSD1", 123456789000L, Side.Sell, 1.1172, 2e6));
                combiner.onSidedPrice(new SidedPrice("EURUSD2", 123456789100L, Side.Buy, 1.1160, 2e6));

                combiner.onSidedPrice(new SidedPrice("EURUSD3", 123456789100L, Side.Sell, 1.1173, 2.5e6));
                combiner.onSidedPrice(new SidedPrice("EURUSD4", 123456789100L, Side.Buy, 1.1167, 1.5e6));
            }

            for (int i = 0; i < 4; i++) {
                // read one message at a time
                try (SingleChronicleQueue in = SingleChronicleQueueBuilder.binary(queuePath)
                        .sourceId(1)
                        .build();
                     SingleChronicleQueue out = SingleChronicleQueueBuilder.binary(queuePath2)
                             .rollCycle(RollCycles.TEST_DAILY)
                             .build()) {

                    MarketDataListener mdListener = out.acquireAppender()
                            .methodWriterBuilder(MarketDataListener.class)
                            .recordHistory(true)
                            .get();
                    SidedMarketDataCombiner combiner = new SidedMarketDataCombiner(mdListener);
                    MethodReader reader = in.createTailer()
                            .afterLastWritten(out)
                            .methodReader(combiner);
                    assertTrue(reader.readOne());

                    System.out.println("OUT:\n" + out.dump());
                }
            }
        } finally {
            try {
                IOTools.shallowDeleteDirWithFiles(queuePath);
                IOTools.shallowDeleteDirWithFiles(queuePath2);
            } catch (Exception e) {

            }
        }
    }
}
