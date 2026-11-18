package com.tiff.screenassistant

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.tiff.screenassistant.data.SoulPreset
import com.tiff.screenassistant.data.SoulRepository

class SoulActivity : AppCompatActivity() {

    private lateinit var soulRepo: SoulRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soul)
        soulRepo = SoulRepository(this)

        findViewById<Button>(R.id.btnSoulBack).setOnClickListener { finish() }

        val etNickname = findViewById<EditText>(R.id.etUserNickname)
        val spinnerPreset = findViewById<Spinner>(R.id.spinnerSoulPreset)
        val etSoulPrompt = findViewById<EditText>(R.id.etSoulPrompt)
        val btnSave = findViewById<Button>(R.id.btnSaveSoul)

        etNickname.setText(soulRepo.userNickname)
        etSoulPrompt.setText(soulRepo.soulDocument)

        val presets = SoulPreset.values()
        spinnerPreset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            presets.map { it.displayName }
        )
        spinnerPreset.setSelection(presets.indexOf(soulRepo.preset))

        var initialSelection = true
        spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (initialSelection) {
                    initialSelection = false
                    return
                }
                val chosen = presets[pos]
                if (chosen != SoulPreset.CUSTOM && chosen.template.isNotBlank()) {
                    etSoulPrompt.setText(chosen.template)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSave.setOnClickListener {
            soulRepo.userNickname = etNickname.text.toString()
            soulRepo.preset = presets[spinnerPreset.selectedItemPosition]
            soulRepo.soulDocument = etSoulPrompt.text.toString()
            Toast.makeText(this, "Saved Tiff's Soul!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
