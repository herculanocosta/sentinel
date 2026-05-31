//
//  SentinelLiveActivityWidget.swift
//  Lock-screen + Dynamic Island UI for the SENTINEL Live Activity. Reads SentinelLiveAttributes
//  (shared from the main app) and renders a small status surface.
//
//  Three presentations Apple requires:
//    1. Lock-screen banner (Apple-themed banner area on the lock screen)
//    2. Dynamic Island compact (small inline pill near the camera notch)
//    3. Dynamic Island expanded (when user long-presses)
//

import SwiftUI
import WidgetKit
import ActivityKit

@available(iOSApplicationExtension 16.1, *)
struct SentinelLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SentinelLiveAttributes.self) { context in
            // Lock-screen / banner presentation.
            LockScreenLiveView(context: context)
                .padding()
                .activityBackgroundTint(Color.black.opacity(0.7))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.serverLabel, systemImage: "antenna.radiowaves.left.and.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    HStack(spacing: 4) {
                        QualityBars(count: context.state.qualityBars)
                        Text("\(context.state.bitrateKbps) kb/s")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.white)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Image(systemName: context.state.isLive ? "dot.radiowaves.left.and.right" : "wifi.exclamationmark")
                            .foregroundStyle(context.state.isLive ? .red : .orange)
                        Text(context.state.stateLabel).font(.callout.weight(.semibold))
                        Spacer()
                        if context.state.isLive {
                            Text(timerInterval: context.state.startedAt...Date.distantFuture,
                                 countsDown: false)
                                .font(.callout.monospacedDigit())
                                .foregroundStyle(.white)
                        }
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.isLive ? "dot.radiowaves.left.and.right" : "wifi.exclamationmark")
                    .foregroundStyle(context.state.isLive ? .red : .orange)
            } compactTrailing: {
                if context.state.isLive {
                    Text(timerInterval: context.state.startedAt...Date.distantFuture,
                         countsDown: false)
                        .font(.caption2.monospacedDigit())
                        .frame(maxWidth: 50)
                } else {
                    Text(context.state.stateLabel).font(.caption2)
                }
            } minimal: {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .foregroundStyle(context.state.isLive ? .red : .orange)
            }
            .keylineTint(.red)
        }
    }
}

@available(iOSApplicationExtension 16.1, *)
private struct LockScreenLiveView: View {
    let context: ActivityViewContext<SentinelLiveAttributes>

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: context.state.isLive ? "dot.radiowaves.left.and.right" : "wifi.exclamationmark")
                .font(.title2)
                .foregroundStyle(context.state.isLive ? .red : .orange)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(context.state.stateLabel).font(.headline)
                    if context.state.isLive {
                        Text(timerInterval: context.state.startedAt...Date.distantFuture,
                             countsDown: false)
                            .font(.subheadline.monospacedDigit())
                    }
                }
                Text(context.attributes.serverLabel)
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                QualityBars(count: context.state.qualityBars)
                Text("\(context.state.bitrateKbps) kb/s")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct QualityBars: View {
    let count: Int
    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<4) { idx in
                Capsule()
                    .fill(idx < count ? barColor(count) : Color.white.opacity(0.25))
                    .frame(width: 3, height: CGFloat(6 + idx * 2))
            }
        }
    }
    private func barColor(_ n: Int) -> Color {
        switch n { case 0,1: return .red; case 2: return .orange; default: return .green }
    }
}
