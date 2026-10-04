package dev.mkzk.manifold;

/**
 * What a sender announces about itself. The hub overwrites [label] and
 * [packageName] with the caller's real identity, so receivers can trust them.
 * New fields go at the end: older readers skip what they do not know.
 */
parcelable SenderInfo {
    String name;
    String label;
    String packageName;
    int width = 0;
    int height = 0;
    int fps = 0;
    boolean hasAudio = false;
}
