package com.example.myvoiceboard

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myvoiceboard.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import java.util.Locale

data class PecCard(val label: String, val symbol: String, val category: String)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private val words = mutableListOf<String>()
    private val cards = listOf(
        PecCard("I want", "🙋", "Core"), PecCard("I need help", "🤝", "Core"),
        PecCard("Yes", "✅", "Core"), PecCard("No", "❌", "Core"),
        PecCard("More", "➕", "Core"), PecCard("Finished", "🏁", "Core"),
        PecCard("Water", "💧", "Food"), PecCard("Snack", "🍎", "Food"),
        PecCard("Breakfast", "🥣", "Food"), PecCard("Lunch", "🥪", "Food"),
        PecCard("Happy", "😊", "Feelings"), PecCard("Sad", "😢", "Feelings"),
        PecCard("Angry", "😠", "Feelings"), PecCard("Worried", "😟", "Feelings"),
        PecCard("Quiet", "🤫", "Feelings"), PecCard("Break", "⏸️", "Activities"),
        PecCard("Bathroom", "🚻", "Activities"), PecCard("Play", "🧸", "Activities"),
        PecCard("Read", "📖", "Activities"), PecCard("Go outside", "🌳", "Activities")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)

        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 5 else 3
        binding.board.layoutManager = GridLayoutManager(this, span)
        setCategory("Core")
        listOf("Core", "Food", "Feelings", "Activities").forEachIndexed { index, name ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = index == 0
                minHeight = 48
                setOnClickListener { setCategory(name) }
            }
            binding.categories.addView(chip)
        }
        binding.speak.setOnClickListener { speakSentence() }
        binding.undo.setOnClickListener { if (words.isNotEmpty()) { words.removeLast(); updateSentence() } }
        binding.clear.setOnClickListener { words.clear(); updateSentence() }
    }

    private fun setCategory(category: String) {
        binding.board.adapter = PecAdapter(cards.filter { it.category == category }) { card ->
            words += card.label
            updateSentence()
            tts.speak(card.label, TextToSpeech.QUEUE_FLUSH, null, "card")
        }
    }

    private fun updateSentence() {
        binding.sentence.text = if (words.isEmpty()) "Tap a card to build a sentence" else words.joinToString(" ")
    }

    private fun speakSentence() {
        if (words.isNotEmpty()) tts.speak(words.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "sentence")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

    override fun onDestroy() {
        tts.stop(); tts.shutdown(); super.onDestroy()
    }
}

class PecAdapter(private val items: List<PecCard>, private val onClick: (PecCard) -> Unit) :
    RecyclerView.Adapter<PecAdapter.Holder>() {
    class Holder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_pec, parent, false)
    ) {
        val symbol: TextView = itemView.findViewById(R.id.symbol)
        val label: TextView = itemView.findViewById(R.id.label)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent)
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.symbol.text = item.symbol
        holder.label.text = item.label
        holder.itemView.contentDescription = item.label
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
