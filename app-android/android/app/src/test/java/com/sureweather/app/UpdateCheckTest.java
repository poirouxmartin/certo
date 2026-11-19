package com.sureweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckTest {

    @Test
    public void compareOrdersNumerically() {
        assertTrue(UpdateCheck.compare("1.6.10", "1.6.9") > 0);
        assertTrue(UpdateCheck.compare("1.6.5", "1.7.0") < 0);
        assertTrue(UpdateCheck.compare("2.0", "1.99.99") > 0);
    }

    @Test
    public void compareIgnoresPrefixAndMissingParts() {
        assertEquals(0, UpdateCheck.compare("v1.6.5", "1.6.5"));
        assertEquals(0, UpdateCheck.compare("1.6", "1.6.0"));
        assertTrue(UpdateCheck.compare("1.6.0.1", "1.6") > 0);
    }

    @Test
    public void parseVersionTrimsAndRejectsGarbage() {
        assertEquals("1.6.6", UpdateCheck.parseVersion("v1.6.6\n"));
        assertEquals("1.7.0", UpdateCheck.parseVersion("  1.7.0 "));
        assertNull(UpdateCheck.parseVersion("<html>captive portal</html>"));
        assertNull(UpdateCheck.parseVersion(""));
        assertNull(UpdateCheck.parseVersion(null));
    }

    @Test
    public void notifiesOnlyForNewerThanInstalled() {
        assertTrue(UpdateCheck.shouldNotify("1.6.6", "1.6.5", null));
        assertFalse(UpdateCheck.shouldNotify("1.6.5", "1.6.5", null));
        assertFalse(UpdateCheck.shouldNotify("1.6.4", "1.6.5", null));
    }

    @Test
    public void notifiesOncePerVersion() {
        assertFalse(UpdateCheck.shouldNotify("1.6.6", "1.6.5", "1.6.6"));
        assertTrue(UpdateCheck.shouldNotify("1.6.7", "1.6.5", "1.6.6"));
        // An older release than one already notified (e.g. a reverted VERSION) stays quiet.
        assertFalse(UpdateCheck.shouldNotify("1.6.6", "1.6.5", "1.6.7"));
    }

    @Test
    public void unknownVersionsNeverNotify() {
        assertFalse(UpdateCheck.shouldNotify(null, "1.6.5", null));
        assertFalse(UpdateCheck.shouldNotify("1.6.6", null, null));
    }

    @Test
    public void notificationIsStaleOnceInstalled() {
        assertTrue(UpdateCheck.isStale("1.6.6", "1.6.6"));
        assertTrue(UpdateCheck.isStale("1.6.6", "1.7.0"));
        assertFalse(UpdateCheck.isStale("1.6.6", "1.6.5"));
        assertFalse(UpdateCheck.isStale(null, "1.6.5"));
    }
}
