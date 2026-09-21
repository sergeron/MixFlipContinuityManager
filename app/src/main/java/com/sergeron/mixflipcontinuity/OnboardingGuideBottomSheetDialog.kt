package com.sergeron.mixflipcontinuity

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sergeron.mixflipcontinuity.databinding.DialogOnboardingGuideBinding

class OnboardingGuideBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogOnboardingGuideBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val PREFS_NAME = "flip_app_prefs"
        private const val KEY_GUIDE_SHOWN = "has_shown_onboarding_guide"

        fun shouldShow(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getBoolean(KEY_GUIDE_SHOWN, false)
        }

        fun markShown(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogOnboardingGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGuideGotIt.setOnClickListener {
            context?.let { markShown(it) }
            dismiss()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
