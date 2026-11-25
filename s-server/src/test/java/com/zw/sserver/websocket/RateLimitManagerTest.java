package com.zw.sserver.websocket;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitManagerTest {

    @Test
    void chatFloodDoesNotConsumeReceiptBudget() {
        RateLimitManager limiter = new RateLimitManager();
        for (int attempt = 0; attempt < 100; attempt++) limiter.tryAcquire(1L);
        assertFalse(limiter.tryAcquire(1L));
        for (int receipt = 0; receipt < 100; receipt++) assertTrue(limiter.tryAcquireReceipt(1L));
        assertFalse(limiter.tryAcquire(1L));
        assertTrue(limiter.tryAcquire(2L));
    }

    @Test
    void receiptFloodIsBoundedAndDisconnectCleansBothBuckets() {
        RateLimitManager limiter = new RateLimitManager();
        boolean rejected = false;
        for (int receipt = 0; receipt < 1000; receipt++) {
            if (!limiter.tryAcquireReceipt(1L)) rejected = true;
        }
        assertTrue(rejected);
        assertTrue(limiter.tryAcquire(1L));
        limiter.remove(1L);
        assertTrue(limiter.tryAcquireReceipt(1L));
        assertTrue(limiter.tryAcquire(1L));
    }
}
