package io.goobi.api.job.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.OffsetDateTime;

import org.junit.Before;
import org.junit.Test;

public class TransactionInfoTest {

    private TransactionInfo transactionInfo;
    private OffsetDateTime now;

    @Before
    public void setUp() {
        transactionInfo = new TransactionInfo();
        now = OffsetDateTime.now();

        transactionInfo.setTransactionId(42);
        transactionInfo.setStartedAt(now.minusMinutes(10));
        transactionInfo.setLastRequest(now);
        transactionInfo.setState(TransactionInfo.StateEnum.ACTIVE);
    }

    @Test
    public void testGetTransactionId() {
        assertEquals(Integer.valueOf(42), transactionInfo.getTransactionId());
    }

    @Test
    public void testGetStartedAt() {
        assertEquals(now.minusMinutes(10), transactionInfo.getStartedAt());
    }

    @Test
    public void testGetLastRequest() {
        assertEquals(now, transactionInfo.getLastRequest());
    }

    @Test
    public void testGetState() {
        assertEquals(TransactionInfo.StateEnum.ACTIVE, transactionInfo.getState());
    }

    @Test
    public void testFluentBuilder() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        OffsetDateTime last = OffsetDateTime.now();

        TransactionInfo info = new TransactionInfo()
                .transactionId(99)
                .startedAt(start)
                .lastRequest(last)
                .state(TransactionInfo.StateEnum.COMMIT);

        assertEquals(Integer.valueOf(99), info.getTransactionId());
        assertEquals(start, info.getStartedAt());
        assertEquals(last, info.getLastRequest());
        assertEquals(TransactionInfo.StateEnum.COMMIT, info.getState());
    }

    @Test
    public void testEnumToString() {
        assertEquals("active", TransactionInfo.StateEnum.ACTIVE.toString());
        assertEquals("commit", TransactionInfo.StateEnum.COMMIT.toString());
        assertEquals("rollback", TransactionInfo.StateEnum.ROLLBACK.toString());
    }

    @Test
    public void testEnumValue() {
        assertEquals("active", TransactionInfo.StateEnum.ACTIVE.value());
        assertEquals("commit", TransactionInfo.StateEnum.COMMIT.value());
        assertEquals("rollback", TransactionInfo.StateEnum.ROLLBACK.value());
    }

    @Test
    public void testToString() {
        String toStringOutput = transactionInfo.toString();
        assertNotNull(toStringOutput);
        assertTrue(toStringOutput.contains("transactionId: 42"));
        assertTrue(toStringOutput.contains("state: active"));
        assertTrue(toStringOutput.contains("startedAt"));
        assertTrue(toStringOutput.contains("lastRequest"));
    }
}
