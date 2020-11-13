/*
 * Copyright 2020, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.service.thread;

import com.yahoo.elide.Elide;
import com.yahoo.elide.async.models.AsyncAPI;
import com.yahoo.elide.async.models.AsyncQuery;
import com.yahoo.elide.async.models.QueryStatus;
import com.yahoo.elide.async.service.DateUtil;
import com.yahoo.elide.async.service.dao.AsyncAPIDAO;
import com.yahoo.elide.core.Path.PathElement;
import com.yahoo.elide.core.filter.FilterPredicate;
import com.yahoo.elide.core.filter.InPredicate;
import com.yahoo.elide.core.filter.LEPredicate;
import com.yahoo.elide.core.filter.expression.AndFilterExpression;
import com.yahoo.elide.core.filter.expression.FilterExpression;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Runnable thread for updating AsyncAPIThread status.
 * beyond the max run time and if not terminated by interrupt process
 * due to app/host crash or restart.
 */
@Slf4j
@Data
@AllArgsConstructor
public class AsyncAPICleanerThread implements Runnable {

    private int maxRunTimeMinutes;
    private Elide elide;
    private int queryCleanupDays;
    private AsyncAPIDAO asyncAPIDao;
    private DateUtil dateUtil = new DateUtil();

    @Override
    public void run() {
        deleteAsyncAPI(AsyncQuery.class);
        timeoutAsyncAPI(AsyncQuery.class);
    }

    /**
     * This method deletes the historical queries based on threshold.
     * @param type AsyncAPI Type Implementation.
     */
    protected <T extends AsyncAPI> void deleteAsyncAPI(Class<T> type) {

        try {
            Date cleanupDate = dateUtil.calculateFilterDate(Calendar.DATE, queryCleanupDays);
            PathElement createdOnPathElement = new PathElement(type, Long.class, "createdOn");
            FilterExpression fltDeleteExp = new LEPredicate(createdOnPathElement, cleanupDate);
            asyncAPIDao.deleteAsyncAPIAndResultCollection(fltDeleteExp, type);
        } catch (Exception e) {
            log.error("Exception in scheduled cleanup: {}", e);
        }
    }

    /**
     * This method updates the status of long running async query which
     * were interrupted due to host crash/app shutdown to TIMEDOUT.
     * @param type AsyncAPI Type Implementation.
     */
    protected <T extends AsyncAPI> void timeoutAsyncAPI(Class<T> type) {

        try {
            Date filterDate = dateUtil.calculateFilterDate(Calendar.MINUTE, maxRunTimeMinutes);
            PathElement createdOnPathElement = new PathElement(type, Long.class, "createdOn");
            PathElement statusPathElement = new PathElement(type, String.class, "status");
            List<QueryStatus> statusList = new ArrayList<QueryStatus>();
            statusList.add(QueryStatus.PROCESSING);
            statusList.add(QueryStatus.QUEUED);
            FilterPredicate inPredicate = new InPredicate(statusPathElement, statusList);
            FilterPredicate lePredicate = new LEPredicate(createdOnPathElement, filterDate);
            AndFilterExpression fltTimeoutExp = new AndFilterExpression(inPredicate, lePredicate);
            asyncAPIDao.updateStatusAsyncAPICollection(fltTimeoutExp, QueryStatus.TIMEDOUT, type);
        } catch (Exception e) {
            log.error("Exception in scheduled cleanup: {}", e);
        }
    }
}