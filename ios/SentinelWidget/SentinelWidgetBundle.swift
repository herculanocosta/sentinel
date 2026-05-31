//
//  SentinelWidgetBundle.swift
//  Entry point for the widget extension. We only ship one Live Activity widget for now; this is
//  where you'd add Home Screen widgets later (e.g. "Tap to start").
//

import SwiftUI
import WidgetKit

@main
struct SentinelWidgetBundle: WidgetBundle {
    var body: some Widget {
        if #available(iOSApplicationExtension 16.1, *) {
            SentinelLiveActivityWidget()
        }
    }
}
