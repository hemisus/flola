package com.hemisus.flola.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * 최근 연 프로젝트 경로 목록을 OS에 영구 저장한다 (Java Preferences 사용 —
 * Windows는 레지스트리, macOS/Linux는 사용자 설정에 저장되어 앱 재시작 후에도 유지).
 *
 * <p>저장하는 경로는 프로젝트 진입 파일({@code project.flola})의 절대경로다.
 * 최신이 맨 앞, 최대 {@link #MAX}개까지 유지한다.</p>
 */
public final class RecentProjects {

    private RecentProjects() {}

    private static final Preferences PREFS = Preferences.userRoot().node("com/hemisus/flola");
    private static final String KEY = "recentProjects";
    private static final String SEP = "\n";
    private static final int    MAX = 8;

    /** 최근 프로젝트 파일 목록 (최신 순). */
    public static List<File> list() {
        String raw = PREFS.get(KEY, "");
        List<File> out = new ArrayList<>();
        if (raw != null && !raw.isBlank())
            for (String p : raw.split(SEP))
                if (!p.isBlank()) out.add(new File(p));
        return out;
    }

    /** 프로젝트를 최근 목록 맨 앞에 추가 (중복 제거, 최대 개수 유지). */
    public static void add(File projectFile) {
        if (projectFile == null) return;
        String path = projectFile.getAbsolutePath();
        List<File> cur = list();
        cur.removeIf(f -> f.getAbsolutePath().equalsIgnoreCase(path));
        cur.add(0, new File(path));
        while (cur.size() > MAX) cur.remove(cur.size() - 1);
        save(cur);
    }

    /** 목록에서 제거 (예: 더 이상 존재하지 않는 경로). */
    public static void remove(File projectFile) {
        if (projectFile == null) return;
        String path = projectFile.getAbsolutePath();
        List<File> cur = list();
        if (cur.removeIf(f -> f.getAbsolutePath().equalsIgnoreCase(path))) save(cur);
    }

    private static void save(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(f.getAbsolutePath());
        }
        PREFS.put(KEY, sb.toString());
    }
}