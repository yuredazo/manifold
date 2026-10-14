enum StreamProfile {
  lowLatency('low', holdMs: 120, keyframeWaitMs: 800, bitratePercent: 70),
  balanced('balanced', holdMs: 300, keyframeWaitMs: 1500, bitratePercent: 100),
  smooth('smooth', holdMs: 900, keyframeWaitMs: 2500, bitratePercent: 140);

  const StreamProfile(this.id, {required this.holdMs, required this.keyframeWaitMs, required this.bitratePercent});

  final String id;
  final int holdMs;
  final int keyframeWaitMs;
  final int bitratePercent;

  static StreamProfile of(String? id) => values.firstWhere((profile) => profile.id == id, orElse: () => balanced);
}
