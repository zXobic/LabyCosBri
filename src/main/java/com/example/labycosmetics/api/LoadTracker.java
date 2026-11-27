/*
 * Cosmetic Bridge LabyCosBri - displays LabyMod cosmetics without the LabyMod client
 * Copyright (C) 2026  zXobic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.labycosmetics.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ladezustand pro Schluessel MIT Wiederholung nach Fehlern.
 * <p>
 * Vorher blieb ein einmal fehlgeschlagener Abruf fuer immer auf FAILED - ein
 * WLAN-Wackler beim Start und der Katalog war bis zum Neustart tot. Jetzt:
 * nach einem Fehler wird mit wachsendem Abstand neu versucht (30 s, 1 min,
 * 5 min, danach alle 5 min).
 * <p>
 * Zu unterscheiden: {@link #failed} = voruebergehend (Timeout, 5xx) -> spaeter
 * nochmal; {@link #absent} = es gibt nichts (404) -> bis zum naechsten
 * {@link #clear} nicht neu fragen.
 */
public final class LoadTracker<K> {

    private static final long[] RETRY_DELAYS_MS = {30_000L, 60_000L, 300_000L};

    public enum Status { LOADING, LOADED, ABSENT, FAILED }

    private record Entry(Status status, long retryAt, int failures) {
    }

    private final Map<K, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Soll der Aufrufer jetzt einen Abruf starten? Wenn ja, ist der Schluessel
     * ab sofort LOADING - ein zweiter Aufruf im selben Frame liefert false.
     */
    public boolean tryStart(K key) {
        long now = System.currentTimeMillis();
        boolean[] start = {false};
        entries.compute(key, (k, e) -> {
            if (e == null || (e.status() == Status.FAILED && now >= e.retryAt())) {
                start[0] = true;
                return new Entry(Status.LOADING, 0L, e == null ? 0 : e.failures());
            }
            return e;
        });
        return start[0];
    }

    public Status status(K key) {
        Entry e = entries.get(key);
        return e == null ? null : e.status();
    }

    public boolean isLoaded(K key) {
        return status(key) == Status.LOADED;
    }

    public void loaded(K key) {
        entries.put(key, new Entry(Status.LOADED, 0L, 0));
    }

    public void absent(K key) {
        entries.put(key, new Entry(Status.ABSENT, 0L, 0));
    }

    public void failed(K key) {
        entries.compute(key, (k, e) -> {
            int failures = (e == null ? 0 : e.failures()) + 1;
            long delay = RETRY_DELAYS_MS[Math.min(failures, RETRY_DELAYS_MS.length) - 1];
            return new Entry(Status.FAILED, System.currentTimeMillis() + delay, failures);
        });
    }

    public void clear() {
        entries.clear();
    }
}
