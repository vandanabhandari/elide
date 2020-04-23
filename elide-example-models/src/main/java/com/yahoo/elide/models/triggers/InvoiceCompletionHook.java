/*
 * Copyright 2020, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */

package com.yahoo.elide.models.triggers;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.functions.LifeCycleHook;
import com.yahoo.elide.models.triggers.services.BillingService;
import com.yahoo.elide.security.ChangeSpec;
import com.yahoo.elide.security.RequestScope;

import java.util.Optional;
import javax.inject.Inject;

/**
 * Test hook which runs when an Invoice is marked complete.
 */
public class InvoiceCompletionHook implements LifeCycleHook<Invoice> {

    @Inject
    private BillingService billingService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation, Invoice invoice,
                        RequestScope requestScope, Optional<ChangeSpec> changes) {
        boolean completeNow = (Boolean) changes.get().getModified();
        boolean completeBefore = (Boolean) changes.get().getOriginal();

        if (completeNow == completeBefore) {
            return;
        }

        if (completeNow) {
            long surcharge = billingService.purchase(invoice);
            invoice.setTotal(invoice.getTotal() + surcharge);
        }
    }
}