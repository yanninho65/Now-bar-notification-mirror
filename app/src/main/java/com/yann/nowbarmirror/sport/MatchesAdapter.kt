package com.yann.nowbarmirror.sport

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yann.nowbarmirror.databinding.ItemMatchBinding

class MatchesAdapter(
    private val matches: List<MatchResult>,
    private val onMatchClicked: (MatchResult) -> Unit
) : RecyclerView.Adapter<MatchesAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemMatchBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val match = matches[position]
        holder.binding.textMatchTitle.text = match.title
        holder.binding.textMatchDetails.text = match.details
        holder.binding.root.setOnClickListener { onMatchClicked(match) }
    }

    override fun getItemCount(): Int = matches.size
}
