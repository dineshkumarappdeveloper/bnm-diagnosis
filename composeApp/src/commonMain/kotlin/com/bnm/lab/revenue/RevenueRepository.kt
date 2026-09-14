package com.bnm.lab.revenue

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bnm.lab.chat.BillingRepository
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Feeds [RevenueReportBuilder] from this computer's database, reactively: a bill
 * saved, a balance collected, an order registered or a sync landing re-emits the
 * report without anyone pressing refresh.
 */
class RevenueRepository(
    private val db: AppDatabase,
    private val billing: BillingRepository,
) {
    fun reportFlow(
        businessId: String,
        range: RevenueRange,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Flow<RevenueReport> {
        val prior = range.prior()
        // A day of slack either side: the builder trims by local date, and the
        // SQL bound is a string compare that sub-second stamps can cross.
        val fromInstant = LabRepository.instantBounds(
            prior.from.minus(DatePeriod(days = 1)).toString(), prior.from.toString(),
        ).first
        val toInstant = LabRepository.instantBounds(
            range.to.toString(), range.to.plus(DatePeriod(days = 1)).toString(),
        ).second

        val lines = db.labOrdersQueries.revenueLines(fromInstant, toInstant).asFlow()
            .mapToList(Dispatchers.Default)
        val linked = db.labOrdersQueries.linkedInvoiceIds().asFlow().mapToList(Dispatchers.Default)
        val names = db.referrersQueries.allNames().asFlow().mapToList(Dispatchers.Default)

        return combine(lines, linked, names, billing.invoiceBalancesFlow(businessId)) { ls, ids, ns, balances ->
            RevenueReportBuilder.build(
                range = range,
                lines = ls.map {
                    RevenueLine(
                        orderId = it.order_id,
                        orderCreatedAt = it.created_at,
                        orderStatus = it.status,
                        referrerId = it.referrer_id,
                        invoiceId = it.invoice_id,
                        testId = it.test_id,
                        testName = it.test_name,
                        price = it.price,
                        commissionPct = it.commission_pct,
                    )
                },
                balances = balances,
                linkedInvoiceIds = ids.filterNotNullTo(HashSet()),
                referrerNames = ns.associate { it.id to it.name },
                timeZone = timeZone,
            )
        }.flowOn(Dispatchers.Default)
    }
}
