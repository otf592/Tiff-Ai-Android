package com.tiff.screenassistant.intents

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.tiff.screenassistant.data.MacroActionType
import com.tiff.screenassistant.data.TelegramRepository
import com.tiff.screenassistant.data.VoiceMacro

object AppIntentExecutor {

    data class ExecutionOutcome(
        val success: Boolean,
        val spokenMessage: String,
        val screenDriveTask: String? = null,
        val telegramBotSendRequest: Pair<String, String>? = null,
        val telegramAppDriveRequest: Pair<String, String>? = null
    )

    fun executeMacro(context: Context, macro: VoiceMacro): ExecutionOutcome {
        return when (macro.actionType) {
            MacroActionType.TELEGRAM_APP_DRIVER -> {
                val recipient = macro.param1.ifBlank { "Saved Messages" }
                val msgText = macro.param2.ifBlank { macro.param3 }.ifBlank { macro.triggerPhrase }
                ExecutionOutcome(
                    success = true,
                    spokenMessage = "Driving Telegram to message $recipient.",
                    telegramAppDriveRequest = Pair(recipient, msgText)
                )
            }
            MacroActionType.TELEGRAM_BOT_MESSAGE -> {
                val tgRepo = TelegramRepository(context)
                val targetChatId = macro.param1.ifBlank { tgRepo.defaultChatId }
                val msgText = macro.param2.ifBlank { macro.param3 }.ifBlank { macro.triggerPhrase }
                ExecutionOutcome(
                    success = true,
                    spokenMessage = "Sending your Telegram bot message now.",
                    telegramBotSendRequest = Pair(targetChatId, msgText)
                )
            }
            MacroActionType.GMAIL_COMPOSE -> launchGmailDraft(
                context = context,
                to = macro.param1,
                subject = macro.param2,
                body = macro.param3
            )
            MacroActionType.CALENDAR_EVENT -> launchCalendarEvent(
                context = context,
                title = macro.param1,
                location = macro.param2,
                description = macro.param3
            )
            MacroActionType.PHONE_CALL -> launchPhoneCall(
                context = context,
                phoneNumber = macro.param1
            )
            MacroActionType.SCREEN_DRIVE -> ExecutionOutcome(
                success = true,
                spokenMessage = "On it—putting together a plan for ${macro.triggerPhrase}.",
                screenDriveTask = macro.param1
            )
        }
    }

    fun launchGmailDraft(context: Context, to: String, subject: String, body: String): ExecutionOutcome {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to.trim()))
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val gmailIntent = Intent(intent).apply { setPackage("com.google.android.gm") }
        return try {
            if (gmailIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(gmailIntent)
            } else {
                context.startActivity(intent)
            }
            ExecutionOutcome(true, "Opening your email draft now.")
        } catch (e: Exception) {
            ExecutionOutcome(false, "I couldn't find an email app to open.")
        }
    }

    fun launchCalendarEvent(
        context: Context,
        title: String,
        location: String,
        description: String
    ): ExecutionOutcome {
        val startMillis = System.currentTimeMillis() + 3600_000L
        val endMillis = startMillis + 3600_000L

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Tiff Scheduled Event" })
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ExecutionOutcome(true, "Opening Calendar to set up ${title.ifBlank { "your event" }}.")
        } catch (e: Exception) {
            ExecutionOutcome(false, "I couldn't open your Calendar app.")
        }
    }

    fun launchPhoneCall(context: Context, phoneNumber: String): ExecutionOutcome {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isBlank()) {
            return ExecutionOutcome(false, "I didn't catch a phone number to call.")
        }
        val uri = Uri.parse("tel:${Uri.encode(cleanNumber)}")
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val verb = if (hasCallPerm) "Calling" else "Dialing"
            ExecutionOutcome(true, "$verb $cleanNumber for you now.")
        } catch (e: Exception) {
            ExecutionOutcome(false, "Sorry, I couldn't start that call.")
        }
    }

    fun tryParseDirectVoiceIntent(context: Context, spokenRaw: String): ExecutionOutcome? {
        val lower = spokenRaw.lowercase().trim()
        val tgRepo = TelegramRepository(context)

        if (lower.startsWith("bot telegram ")) {
            val msg = spokenRaw.substringAfter("telegram ").trim()
            if (msg.isNotEmpty()) {
                return ExecutionOutcome(
                    success = true,
                    spokenMessage = "Sending via Telegram bot.",
                    telegramBotSendRequest = Pair(tgRepo.defaultChatId, msg)
                )
            }
        }

        if (lower.startsWith("telegram ")) {
            val rest = spokenRaw.substringAfter(" ").trim()
            val words = rest.split(" ", limit = 2)
            if (words.size == 2) {
                val recipient = words[0].trim().removeSuffix(",")
                val body = words[1].trim()
                return ExecutionOutcome(
                    success = true,
                    spokenMessage = "Opening Telegram to message $recipient.",
                    telegramAppDriveRequest = Pair(recipient, body)
                )
            }
        }

        if (lower.contains(" on telegram ")) {
            val before = spokenRaw.substringBefore(" on telegram ").trim()
            val message = spokenRaw.substringAfter(" on telegram ").trim()
            val recipient = before
                .removePrefix("message ")
                .removePrefix("Message ")
                .removePrefix("text ")
                .removePrefix("Text ")
                .removePrefix("tell ")
                .removePrefix("Tell ")
                .trim()
            if (recipient.isNotEmpty() && message.isNotEmpty()) {
                return ExecutionOutcome(
                    success = true,
                    spokenMessage = "Driving Telegram to message $recipient.",
                    telegramAppDriveRequest = Pair(recipient, message)
                )
            }
        }

        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val target = spokenRaw.substringAfter(" ").trim()
            val digitsOnly = target.filter { it.isDigit() || it == '+' }
            if (digitsOnly.length >= 3) {
                return launchPhoneCall(context, digitsOnly)
            }
        }

        if (lower.startsWith("schedule event ") || lower.startsWith("create event ")) {
            val title = spokenRaw.substringAfter("event ").trim()
            return launchCalendarEvent(context, title, "", "Scheduled by Tiff")
        }

        if (lower.startsWith("compose email to ") || lower.startsWith("email ")) {
            val rest = if (lower.startsWith("compose email to ")) {
                spokenRaw.substringAfter("to ").trim()
            } else {
                spokenRaw.substringAfter("email ").trim()
            }
            val parts = rest.split(" about ", ignoreCase = true, limit = 2)
            val recipient = parts[0].trim()
            val subject = if (parts.size > 1) parts[1].trim() else ""
            if (recipient.contains("@")) {
                return launchGmailDraft(context, recipient, subject, "")
            }
        }

        return null
    }
}
