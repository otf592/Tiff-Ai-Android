package com.tiff.screenassistant

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.tiff.screenassistant.data.MemoryCategory
import com.tiff.screenassistant.data.MemoryRepository

class MemoryActivity : AppCompatActivity() {

    private lateinit var memoryRepo: MemoryRepository
    private lateinit var containerList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        memoryRepo = MemoryRepository(this)
        containerList = findViewById(R.id.containerMemoriesList)

        findViewById<Button>(R.id.btnMemoryBack).setOnClickListener { finish() }

        val spinnerCat = findViewById<Spinner>(R.id.spinnerMemoryCategory)
        val etFact = findViewById<EditText>(R.id.etMemoryFact)
        val tvRecent = findViewById<TextView>(R.id.tvRecentContext)

        val categories = MemoryCategory.values()
        spinnerCat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories.map { it.label }
        )

        val recentEvents = memoryRepo.getRecentScreenEvents()
        if (recentEvents.isNotEmpty()) {
            tvRecent.text = recentEvents.joinToString("\n")
        }

        findViewById<Button>(R.id.btnAddMemory).setOnClickListener {
            val fact = etFact.text.toString().trim()
            if (fact.isEmpty()) {
                Toast.makeText(this, "Enter something for Tiff to remember.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            memoryRepo.addLongTermMemory(fact, categories[spinnerCat.selectedItemPosition])
            etFact.text.clear()
            refreshMemoryList()
            Toast.makeText(this, "Tiff will remember that!", Toast.LENGTH_SHORT).show()
        }

        refreshMemoryList()
    }

    private fun refreshMemoryList() {
        containerList.removeAllViews()
        val items = memoryRepo.getAllLongTerm()

        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1D24"))
                setPadding(30, 22, 30, 22)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 18 }
                layoutParams = lp
            }

            val badgeTv = TextView(this).apply {
                text = "${item.category.label} • ${item.timestamp}"
                setTextColor(Color.parseColor("#0D9488"))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val factTv = TextView(this).apply {
                text = item.fact
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 8, 0, 12)
            }

            val btnForget = Button(this).apply {
                text = "Forget"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                setOnClickListener {
                    memoryRepo.deleteLongTermMemory(item.id)
                    refreshMemoryList()
                }
            }

            card.addView(badgeTv)
            card.addView(factTv)
            card.addView(btnForget)
            containerList.addView(card)
        }
    }
}
