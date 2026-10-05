package dev.mkzk.manifold;

/** The hub overwrites [label] and [packageName] with the caller's real identity. New fields go at the end. */
parcelable SenderInfo {
    String name;
    String label;
    String packageName;
    int width = 0;
    int height = 0;
    int fps = 0;
    boolean hasAudio = false;
}
