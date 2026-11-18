package com.tiff.screenassistant

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tiff.screenassistant.data.MacroActionType
import com.tiff.screenassistant.data.MacroRepository
import com.tiff.screenassistant.data.TelegramRepository
import com.tiff.screenassistant.data.VoiceMacro
import com.tiff.screenassistant.intents.AppIntentExecutor
import com.tiff.screenassistant.telegram.TelegramAppAutoDriver
import com.tiff.screenassistant.telegram.TelegramBotClient
import kotlinx.coroutines.launch

class MacrosActivity : AppCompatActivity() {

    private lateinit var repo: MacroRepository
    private lateinit var containerList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_macros)
        repo = MacroRepository(this)
        containerList = findViewById(R.id.containerMacrosList)

        findViewById<Button>(R.id.btnBackMain).setOnClickListener { finish() }

        val spinner = findViewById<Spinner>(R.id.spinnerMacroType)
        val etTrigger = findViewById<EditText>(R.id.etMacroTrigger)
        val etParam1 = findViewById<EditText>(R.id.etMacroParam1)
        val etParam2 = findViewById<EditText>(R.id.etMacroParam2)
        val etParam3 = findViewById<EditText>(R.id.etMacroParam3)
        val tvParam1 = findViewById<TextView>(R.id.tvParam1Label)
        val tvParam2 = findViewById<TextView>(R.id.tvParam2Label)
        val tvParam3 = findViewById<TextView>(R.id.tvParam3Label)

        val types = MacroActionType.values()
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            types.map { it.label }
        )

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                when (types[pos]) {
                    MacroActionType.TELEGRAM_APP_DRIVER -> {
                        tvParam1.text = "Telegram Contact Name, @username, or +phone"
                        etParam1.hint = "e.g. Sarah or @username or Saved Messages"
                        tvParam2.visibility = View.VISIBLE
                        etParam2.visibility = View.VISIBLE
                        tvParam2.text = "Telegram Message Text"
                        etParam2.hint = "e.g. Heading home now!"
                        tvParam3.visibility = View.GONE
                        etParam3.visibility = View.GONE
                    }
                    MacroActionType.TELEGRAM_BOT_MESSAGE -> {
                        tvParam1.text = "Target Chat ID (Leave blank for Default Chat)"
                        etParam1.hint = "e.g. 987654321 or blank"
                        tvParam2.visibility = View.VISIBLE
                        etParam2.visibility = View.VISIBLE
                        tvParam2.text = "Telegram Bot Message Text"
                        etParam2.hint = "e.g. Status update from Tiff"
                        tvParam3.visibility = View.GONE
                        etParam3.visibility = View.GONE
                    }
                    MacroActionType.GMAIL_COMPOSE -> {
                        tvParam1.text = "Recipient Email Address"
                        etParam1.hint = "client@example.com"
                        tvParam2.visibility = View.VISIBLE
                        etParam2.visibility = View.VISIBLE
                        tvParam2.text = "Email Subject"
                        etParam2.hint = "Invoice #104"
                        tvParam3.visibility = View.VISIBLE
                        etParam3.visibility = View.VISIBLE
                        tvParam3.text = "Email Body"
                        etParam3.hint = "Hello, please find attached..."
                    }
                    MacroActionType.CALENDAR_EVENT -> {
                        tvParam1.text = "Event Title"
                        etParam1.hint = "Client Strategy Call"
                        tvParam2.visibility = View.VISIBLE
                        etParam2.visibility = View.VISIBLE
                        tvParam2.text = "Location / Link"
                        etParam2.hint = "Google Meet"
                        tvParam3.visibility = View.VISIBLE
                        etParam3.visibility = View.VISIBLE
                        tvParam3.text = "Event Description"
                        etParam3.hint = "Discuss Q4 roadmap"
                    }
                    MacroActionType.PHONE_CALL -> {
                        tvParam1.text = "Phone Number"
                        etParam1.hint = "+15551234567"
                        tvParam2.visibility = View.GONE
                        etParam2.visibility = View.GONE
                        tvParam3.visibility = View.GONE
                        etParam3.visibility = View.GONE
                    }
                    MacroActionType.SCREEN_DRIVE -> {
                        tvParam1.text = "Screen Drive Goal"
                        etParam1.hint = "e.g. open Notes and create a new note"
                        tvParam2.visibility = View.GONE
                        etParam2.visibility = View.GONE
                        tvParam3.visibility = View.GONE
                        etParam3.visibility = View.GONE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnAddMacro).setOnClickListener {
            val trigger = etTrigger.text.toString().trim()
            val p1 = etParam1.text.toString().trim()
            val p2 = etParam2.text.toString().trim()
            val chosenType = types[spinner.selectedItemPosition]
            val isTelegram = chosenType == MacroActionType.TELEGRAM_APP_DRIVER ||
                    chosenType == MacroActionType.TELEGRAM_BOT_MESSAGE

            if (trigger.isEmpty() || (!isTelegram && p1.isEmpty()) || (isTelegram && p2.isEmpty())) {
                Toast.makeText(this, "Fill in the trigger phrase and required field.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val macro = VoiceMacro(
                triggerPhrase = trigger,
                actionType = chosenType,
                param1 = p1,
                param2 = p2,
                param3 = etParam3.text.toString().trim()
            )
            repo.addMacro(macro)
            etTrigger.text.clear()
            etParam1.text.clear()
            etParam2.text.clear()
            etParam3.text.clear()
            refreshMacrosList()
            Toast.makeText(this, "Saved macro: \"$trigger\"", Toast.LENGTH_SHORT).show()
        }

        refreshMacrosList()
    }

    private fun refreshMacrosList() {
        containerList.removeAllViews()
        val macros = repo.getAll()

        for (macro in macros) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1D24"))
                setPadding(32, 24, 32, 24)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
                layoutParams = lp
            }

            val titleTv = TextView(this).apply {
                text = "\"${macro.triggerPhrase}\""
                setTextColor(Color.parseColor("#EC4899"))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val targetSummary = when (macro.actionType) {
                MacroActionType.TELEGRAM_APP_DRIVER -> "${macro.param1.ifBlank { "Saved Messages" }}: ${macro.param2}"
                MacroActionType.TELEGRAM_BOT_MESSAGE -> macro.param2
                else -> macro.param1
            }

            val subTv = TextView(this).apply {
                text = "${macro.actionType.label} -> $targetSummary"
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 13f
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
            }

            val btnRun = Button(this).apply {
                text = "Test Intent"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2563EB"))
                setOnClickListener {
                    val res = AppIntentExecutor.executeMacro(this@MacrosActivity, macro)
                    Toast.makeText(this@MacrosActivity, res.spokenMessage, Toast.LENGTH_SHORT).show()
                    res.telegramAppDriveRequest?.let { (recipient, msg) ->
                        val driver = TelegramAppAutoDriver(this@MacrosActivity, TelegramRepository(this@MacrosActivity))
                        lifecycleScope.launch {
                            driver.driveSendPersonalMessage(recipient, msg) { status ->
                                Toast.makeText(this@MacrosActivity, status, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    res.telegramBotSendRequest?.let { (chatId, msg) ->
                        val client = TelegramBotClient(TelegramRepository(this@MacrosActivity))
                        lifecycleScope.launch {
                            client.sendMessage(msg, chatId)
                        }
                    }
                }
            }

            val btnDelete = Button(this).apply {
                text = "Delete"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 16 }
                layoutParams = lp
                setOnClickListener {
                    repo.deleteMacro(macro.id)
                    refreshMacrosList()
                }
            }

            btnRow.addView(btnRun)
            btnRow.addView(btnDelete)
            card.addView(titleTv)
            card.addView(subTv)
            card.addView(btnRow)
            containerList.addView(card)
        }
    }
}
