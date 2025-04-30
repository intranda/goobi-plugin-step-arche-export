package io.goobi.api.job.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.OffsetDateTime;

import org.goobi.api.rest.TransactionInfo;
import org.junit.Before;
import org.junit.Test;

public class TransactionInfoTest {

    private TransactionInfo transactionInfo;
    private OffsetDateTime now;

    @Before
    public void setUp() {
        transactionInfo = new TransactionInfo();
        now = OffsetDateTime.now();

        transactionInfo.setTransactionId(42l);
        transactionInfo.setStartedAt(now.minusMinutes(10).toString());
        transactionInfo.setLastRequest(now.toString());
        transactionInfo.setState("active");
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
        assertEquals("active", transactionInfo.getState());
    }

    @Test
    public void testFluentBuilder() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        OffsetDateTime last = OffsetDateTime.now();

        TransactionInfo info = new TransactionInfo()
                .transactionId(99l)
                .startedAt(start.toString())
                .lastRequest(last.toString())
                .state("commit");

        assertEquals(Integer.valueOf(99), info.getTransactionId());
        assertEquals(start, info.getStartedAt());
        assertEquals(last, info.getLastRequest());
        assertEquals("commit", info.getState());
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
