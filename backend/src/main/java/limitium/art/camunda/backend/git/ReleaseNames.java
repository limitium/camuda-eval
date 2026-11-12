package limitium.art.camunda.backend.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release branches and tags are named by version numbers: a release branch {@code release/X.Y} or
 * {@code release/X.Y.Z}, a tag {@code vX.Y.Z}. They are ordered by those numbers, never as text, so
 * release/1.10 comes after release/1.9 and release/1.0.1 between release/1.0 and release/1.1.
 *
 * <p>A tag records the branch it was made on in its annotation; a tag made before that is taken to
 * belong to the release branch named by its first two numbers (v1.0.3 to release/1.0).
 */
public final class ReleaseNames {

    public static final String PREFIX = "release/";
    /** The annotation line that says which release branch a tag was made on. */
    static final String BRANCH_TRAILER = "camunda-eval-branch: ";

    private static final Pattern NUMBER = Pattern.compile("(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})(?:\\.(0|[1-9]\\d{0,5}))?");
    private static final Pattern TAG = Pattern.compile("v(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})");

    private ReleaseNames() {
    }

    /** The numbers of "1.2" or "1.2.3" (a missing third is 0); null when it is not a version number. */
    public static int[] numbers(String version) {
        Matcher m = version == null ? null : NUMBER.matcher(version.trim());
        if (m == null || !m.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    /** The numbers of a release branch, or null when the name is not one. */
    public static int[] ofBranch(String branch) {
        return branch != null && branch.startsWith(PREFIX) ? numbers(branch.substring(PREFIX.length())) : null;
    }

    public static boolean isBranch(String name) {
        return ofBranch(name) != null;
    }

    /** The numbers of a tag vX.Y.Z, or null when the name is not one. */
    public static int[] ofTag(String tag) {
        Matcher m = tag == null ? null : TAG.matcher(tag);
        if (m == null || !m.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    public static boolean isTag(String name) {
        return ofTag(name) != null;
    }

    /** A tag name from what a person typed: "1.2.3" or "v1.2.3" is v1.2.3; anything else is null. */
    public static String tagFrom(String typed) {
        if (typed == null) {
            return null;
        }
        String t = typed.trim();
        String name = t.startsWith("v") ? t : "v" + t;
        return isTag(name) ? name : null;
    }

    public static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** A key that sorts as the numbers do; for a name that is not a release branch, the name. */
    public static String branchKey(String branch) {
        int[] n = ofBranch(branch);
        return n == null ? branch : key(n);
    }

    public static String tagKey(String tag) {
        int[] n = ofTag(tag);
        return n == null ? tag : key(n);
    }

    static String key(int[] n) {
        return String.format("%06d.%06d.%06d", n[0], n[1], n[2]);
    }

    public static String show(int[] n) {
        return n[0] + "." + n[1] + "." + n[2];
    }

    /** The release branch a tag was made on: as its annotation records it, or by its numbers for an older tag. */
    public static String branchOfTag(String tag, String annotation) {
        if (annotation != null) {
            for (String line : annotation.split("\n")) {
                if (line.startsWith(BRANCH_TRAILER)) {
                    String branch = line.substring(BRANCH_TRAILER.length()).trim();
                    if (isBranch(branch)) {
                        return branch;
                    }
                }
            }
        }
        int[] n = ofTag(tag);
        return n == null ? null : PREFIX + n[0] + "." + n[1];
    }

    /** The annotation text a tag carries: what is in it, then which branch it was made on. */
    static String annotation(String notes, String branch) {
        return notes + "\n\n" + BRANCH_TRAILER + branch + "\n";
    }
}
