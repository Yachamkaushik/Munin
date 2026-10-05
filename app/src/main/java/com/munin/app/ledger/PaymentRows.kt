package com.munin.app.ledger

import com.munin.app.data.PaymentWithItem
import com.munin.app.extract.PaymentDirection
import com.munin.app.extract.PaymentOutcome

fun PaymentWithItem.toRow() = PaymentRow(
    payment.id, payment.itemId, uri, displayName, payment.app,
    PaymentOutcome.valueOf(payment.outcome), PaymentDirection.valueOf(payment.direction),
    payment.amountPaise, payment.amountConfidence, payment.payee, payment.upiId, payment.paidDate, payment.paidTime,
    payment.reference, payment.problem, payment.userDecision,
)
