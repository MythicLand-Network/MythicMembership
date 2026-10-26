package cn.mythicland.memberlib;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MemberTimeline {
    private MemberTimeline() {
    }

    static List<MemberRecord> resolve(List<MemberRecord> records) {
        Map<MemberKey, MemberRecord> source = new HashMap<>();
        Map<MemberKey, MemberRecord> resolved = new HashMap<>();
        records.forEach(record -> source.put(record.key(), record));
        return records.stream().map(record -> resolve(record, source, resolved)).toList();
    }

    private static MemberRecord resolve(
            MemberRecord record,
            Map<MemberKey, MemberRecord> source,
            Map<MemberKey, MemberRecord> resolved
    ) {
        MemberRecord cached = resolved.get(record.key());
        if (cached != null) return cached;
        MemberRecord result = record;
        if (record.pause() != null) {
            MemberRecord blocker = source.get(record.pause().blocker());
            if (blocker == null) throw new IllegalStateException("Paused membership blocker was not found: " + record.pause().blocker());
            blocker = resolve(blocker, source, resolved);
            Instant resume = blocker.permanent() || (blocker.pause() != null && blocker.pause().resumesAt() == null)
                    ? null : blocker.expiresAt();
            Instant expiry = resume == null || record.permanent()
                    ? record.expiresAt() : resume.plusMillis(record.pause().remainingMillis());
            result = new MemberRecord(record.playerUniqueId(), record.key(), record.scope(), expiry,
                    new MemberRecord.Pause(record.pause().blocker(), record.pause().remainingMillis(), resume));
        }
        resolved.put(record.key(), result);
        return result;
    }
}
