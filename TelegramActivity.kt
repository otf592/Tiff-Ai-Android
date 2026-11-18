package com.tiff.screenassistant

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tiff.screenassistant.data.TelegramRepository
import com.tiff.screenassistant.telegram.TelegramAppAutoDriver
import com.tiff.screenassistant.telegram.TelegramBotClient
import kotlinx.coroutines.launch

class TelegramActivity : AppCompatActivity() {

    private lateinit var tgRepo: TelegramRepository
    private lateinit var tgClient: TelegramBotClient
    private lateinit var tgAppDriver: TelegramAppAutoDriver
    private lateinit var containerContacts: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram)
        tgRepo = TelegramRepository(this)
        tgClient = TelegramBotClient(tgRepo)
        tgAppDriver = TelegramAppAutoDriver(this, tgRepo)
        containerContacts = findViewById(R.id.containerTgContacts)

        findViewById<Button>(R.id.btnTelegramBack).setOnClickListener { finish() }

        val cbAutoTapSend = findViewById<CheckBox>(R.id.cbAutoTapSend)
        val etTestRecipient = findViewById<EditText>(R.id.etTestDriverRecipient)
        val etTestMessage = findViewById<EditText>(R.id.etTestDriverMessage)

        cbAutoTapSend.isChecked = tgRepo.autoTapSendInApp
        cbAutoTapSend.setOnCheckedChangeListener { _, isChecked ->
            tgRepo.autoTapSendInApp = isChecked
        }

        findViewById<Button>(R.id.btnRunTestDriver).setOnClickListener {
            val recipient = etTestRecipient.text.toString().trim()
            val message = etTestMessage.text.toString().trim()
            if (recipient.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Enter a contact name/@username and message.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tgRepo.autoTapSendInApp = cbAutoTapSend.isChecked
            lifecycleScope.launch {
                tgAppDriver.driveSendPersonalMessage(recipient, message) { status ->
                    Toast.makeText(this@TelegramActivity, status, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val etToken = findViewById<EditText>(R.id.etTelegramToken)
        val etChatId = findViewById<EditText>(R.id.etTelegramChatId)
        val cbPolling = findViewById<CheckBox>(R.id.cbTelegramPolling)

        etToken.setText(tgRepo.botToken)
        etChatId.setText(tgRepo.defaultChatId)
        cbPolling.isChecked = tgRepo.pollingEnabled

        findViewById<Button>(R.id.btnDetectChatId).setOnClickListener {
            tgRepo.botToken = etToken.text.toString()
            lifecycleScope.launch {
                val res = tgClient.autoDetectLatestChatId()
                res.onSuccess { (id, title) ->
                    etChatId.setText(id)
                    tgRepo.defaultChatId = id
                    Toast.makeText(this@TelegramActivity, "Found chat: $title ($id)", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this@TelegramActivity, it.message ?: "Failed to detect Chat ID", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnSaveTelegram).setOnClickListener {
            tgRepo.autoTapSendInApp = cbAutoTapSend.isChecked
            tgRepo.botToken = etToken.text.toString()
            tgRepo.defaultChatId = etChatId.text.toString()
            tgRepo.pollingEnabled = cbPolling.isChecked
            Toast.makeText(this, "Saved all Telegram settings!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTestTelegram).setOnClickListener {
            tgRepo.botToken = etToken.text.toString()
            tgRepo.defaultChatId = etChatId.text.toString()
            lifecycleScope.launch {
                val res = tgClient.sendMessage("Hey! Tiff's Telegram bot bridge is connected.")
                res.onSuccess {
                    Toast.makeText(this@TelegramActivity, it, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@TelegramActivity, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val etContactName = findViewById<EditText>(R.id.etTgContactName)
        val etContactChatId = findViewById<EditText>(R.id.etTgContactChatId)

        findViewById<Button>(R.id.btnAddTgContact).setOnClickListener {
            val name = etContactName.text.toString().trim()
            val cId = etContactChatId.text.toString().trim()
            if (name.isEmpty() || cId.isEmpty()) {
                Toast.makeText(this, "Enter both a name and Chat ID.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tgRepo.addContact(name, cId)
            etContactName.text.clear()
            etContactChatId.text.clear()
            refreshContactsList()
            Toast.makeText(this, "Saved bot chat shortcut: $name", Toast.LENGTH_SHORT).show()
        }

        refreshContactsList()
    }

    private fun refreshContactsList() {
        containerContacts.removeAllViews()
        val contacts = tgRepo.getContacts()
        for (contact in contacts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#1A1D24"))
                setPadding(28, 20, 28, 20)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14 }
                layoutParams = lp
            }

            val infoTv = TextView(this).apply {
                text = "${contact.name}  (${contact.chatId})"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDel = Button(this).apply {
                text = "Delete"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                setOnClickListener {
                    tgRepo.deleteContact(contact.name)
                    refreshContactsList()
                }
            }

            row.addView(infoTv)
            row.addView(btnDel)
            containerContacts.addView(row)
        }
    }
}
