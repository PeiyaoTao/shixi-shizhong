# Third-party software

Fold.java and RearSession.java are adapted from https://github.com/reveille45/fold-toggle (MIT).
Original sources and the complete license are preserved in third_party/fold-toggle/.
Modification: Java package changed to com.foldstandby.clock. The app uses runtime device-state discovery and the original optional OEM extension fallback; device compatibility is not guaranteed.

No Folio or Fold Ambient code is included. Clock rendering and posture detection were independently implemented.

## Lyric Getter broadcast compatibility

The app can receive compatible `Lyric_Data` broadcasts. The in-tree [`LyricData` class](app/src/main/java/cn/lyric/getter/api/data/LyricData.java) implements the parcel format. See the [Lyric Getter API project](https://github.com/xiaowine/Lyric-Getter-Api) for the upstream interface. The Gradle build has no dependency on the upstream SDK.
