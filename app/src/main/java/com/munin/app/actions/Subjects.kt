package com.munin.app.actions

import com.munin.app.answer.Answer
import com.munin.app.data.FactEntity
import com.munin.app.extract.FactType

/** Extraction confidence under which the dialog warns the user to check the value. */
const val LOW_CONFIDENCE_BELOW = 0.6f

fun Answer.toSubject() = ActionSubject(kind, value, label, raw, itemTitle, source.displayName, factConfidence < LOW_CONFIDENCE_BELOW || value.startsWith("--"))

fun FactEntity.toSubject(itemTitle: String, sourceName: String) =
    ActionSubject(FactType.valueOf(name), value, label, raw, itemTitle, sourceName, confidence < LOW_CONFIDENCE_BELOW || value.startsWith("--"))
