package com.wargame.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Set;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class RadarEvents {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RadarEvents.class);
    private final ObjectProvider<RadarDispatcher> dispatcher;
    private final Object transactionKey = new Object();

    public void changed() {
        changed(null);
    }

    @SuppressWarnings("unchecked")
    public void changed(Long owner) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        Set<Long> existing = (Set<Long>) TransactionSynchronizationManager.getResource(transactionKey);
        if (existing != null) {
            if (owner != null) existing.add(owner);
            return;
        }
        Set<Long> affected = new TreeSet<>();
        if (owner != null) affected.add(owner);
        TransactionSynchronizationManager.bindResource(transactionKey, affected);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { dispatcher.getObject().refresh(Set.copyOf(affected)); }
                catch (RuntimeException failure) { log.warn("Radar recovery will retry committed changes: {}", failure.getMessage()); }
            }
            @Override public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(transactionKey);
            }
        });
    }
}
