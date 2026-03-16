package com.hdtodayzjosef

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDTodayZJosefPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDTodayZJosef())
    }
}
