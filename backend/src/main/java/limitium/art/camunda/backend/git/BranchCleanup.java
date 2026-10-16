package limitium.art.camunda.backend.git;

import com.fasterxml.jackson.databind.ObjectMapper;
import limitium.art.camunda.backend.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tidying old branches without ever losing work.
 *
 * <ul>
 *   <li>A space with nothing its line lacks is removed outright once idle for a short grace
 *       period: every commit in it is already somewhere else, and nobody is about to start in it.</li>
 *   <li>A space untouched past the stale limit, with work that never landed, is <em>archived</em>: its
 *       newest commit is kept under {@code refs/archive/} with who, when and why, and it can be
 *       restored as a space in one step.</li>
 *   <li>A stale space with unsaved edits is left alone; only its owner can decide what those are
 *       worth, by saving a version.</li>
 *   <li>A release line older than the newest few is closed only if its newest commit is a released
 *       version and no open space lands in it. Its versions stay, so a fix to one recreates the line.</li>
 *   <li>The newest work is never touched.</li>
 * </ul>
 *
 * Applying is all or nothing and checked against what the caller was shown: if any branch moved or
 * any verdict changed since, nothing is done and the fresh plan comes back instead.
 */
public class BranchCleanup {

    public static final String ARCHIVE_PREFIX = "refs/archive/";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Action { REMOVE, ARCHIVE, CLOSE, BLOCKED }

    public enum Reason {
        /** Everything in the space is already in its line (landed, or never changed). */
        NOTHING_UNIQUE,
        /** The space's newest saved version landed: its work lives in the lines now. */
        LANDED,
        /** Untouched past the limit, with work not in any line: kept in the archive. */
        STALE_UNLANDED,
        /** Unsaved edits: only the owner can say what they are worth. */
        UNSAVED_EDITS,
        /** An old line whose newest commit is a released version. */
        FULLY_RELEASED,
        /** One of the newest lines, fully released: can be closed, but is kept unless someone chooses to. */
        FULLY_RELEASED_RECENT,
        /** An old line with commits after its last version. */
        UNRELEASED_CHANGES,
        /** An old line that an open space will land in. */
        OPEN_SPACE_LANDS_HERE
    }

    public static final class Item {
        public final String id;
        public final String kind;          // SPACE | LINE
        public final Action action;
        public final Reason reason;
        public final String owner;
        public final String name;
        public final String head;
        public final String lastActivity;
        public final String lastMessage;
        /** Supporting facts for the reason: the line work already sits in, the spaces landing here… */
        public final List<String> detail;

        Item(String id, String kind, Action action, Reason reason, String owner, String name,
             String head, String lastActivity, String lastMessage, List<String> detail) {
            this.id = id;
            this.kind = kind;
            this.action = action;
            this.reason = reason;
            this.owner = owner;
            this.name = name;
            this.head = head;
            this.lastActivity = lastActivity;
            this.lastMessage = lastMessage;
            this.detail = detail;
        }
    }

    public static final class Archived {
        public final String ref;
        public final String branch;
        public final String owner;
        public final String name;
        public final String sha;
        public final String lastMessage;
        public final String archivedAt;
        public final String archivedBy;
        public final String baseline;
        public final String targetLine;

        Archived(String ref, String branch, String owner, String name, String sha, String lastMessage,
                 String archivedAt, String archivedBy, String baseline, String targetLine) {
            this.ref = ref;
            this.branch = branch;
            this.owner = owner;
            this.name = name;
            this.sha = sha;
            this.lastMessage = lastMessage;
            this.archivedAt = archivedAt;
            this.archivedBy = archivedBy;
            this.baseline = baseline;
            this.targetLine = targetLine;
        }
    }

    public static final class Plan {
        public final int staleAfterDays;
        public final int keepReleaseLines;
        public final List<Item> items;
        /** Spaces in use, and lines kept open: counted so the page can say what is left alone. */
        public final int spacesInUse;
        public final List<String> linesKept;

        Plan(int staleAfterDays, int keepReleaseLines, List<Item> items, int spacesInUse, List<String> linesKept) {
            this.staleAfterDays = staleAfterDays;
            this.keepReleaseLines = keepReleaseLines;
            this.items = items;
            this.spacesInUse = spacesInUse;
            this.linesKept = linesKept;
        }
    }

    /** The plan moved under the caller: nothing was done, and here is the fresh one. */
    public static final class PlanChanged extends RuntimeException {
        public final Plan plan;

        PlanChanged(String message, Plan plan) {
            super(message);
            this.plan = plan;
        }
    }

    private final GitRuleStore store;
    private final GitProperties properties;

    public BranchCleanup(GitRuleStore store, GitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    // --- the plan ---------------------------------------------------------------------------------

    public Plan plan() throws IOException, GitAPIException {
        return plan(Instant.now());
    }

    Plan plan(Instant now) throws IOException, GitAPIException {
        List<Item> items = new ArrayList<>();
        Duration stale = Duration.ofDays(properties.getStaleAfterDays());
        Duration grace = Duration.ofDays(properties.getIdleGraceDays());
        List<Workspace> spaces = store.workspaces();
        int inUse = 0;

        for (Workspace space : spaces) {
            WorkspaceId id = WorkspaceId.parse(space.id);
            Instant last = store.lastActivity(id, space.lastModifiedAt);
            boolean isStale = last.plus(stale).isBefore(now);
            List<String> detail = new ArrayList<>();
            detail.add(space.targetLine);
            boolean idle = last.plus(grace).isBefore(now);
            // Landed: done with, offered straight away. The grace only protects a space just opened.
            if (space.ahead == 0 && !space.dirty && store.landing(id, space.targetLine) != null) {
                items.add(new Item(space.id, "SPACE", Action.REMOVE, Reason.LANDED, space.owner,
                        space.name, space.head, last.toString(), space.lastMessage, detail));
            } else if (space.ahead == 0 && !space.dirty && idle) {
                items.add(new Item(space.id, "SPACE", Action.REMOVE, Reason.NOTHING_UNIQUE, space.owner,
                        space.name, space.head, last.toString(), space.lastMessage, detail));
            } else if (isStale && space.dirty) {
                items.add(new Item(space.id, "SPACE", Action.BLOCKED, Reason.UNSAVED_EDITS, space.owner,
                        space.name, space.head, last.toString(), space.lastMessage, detail));
            } else if (isStale && space.ahead > 0) {
                detail.add(String.valueOf(space.ahead));
                items.add(new Item(space.id, "SPACE", Action.ARCHIVE, Reason.STALE_UNLANDED, space.owner,
                        space.name, space.head, last.toString(), space.lastMessage, detail));
            } else {
                inUse++;
            }
        }

        List<String> kept = new ArrayList<>();
        try (Git git = Git.open(store.mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repository = git.getRepository();
            Map<ObjectId, List<String>> versionsAt = new LinkedHashMap<>();
            for (Ref ref : git.tagList().call()) {
                String tag = ref.getName().substring(Constants.R_TAGS.length());
                if (tag.matches("v\\d+\\.\\d+\\.\\d+")) {
                    ObjectId peeled = repository.getRefDatabase().peel(ref).getPeeledObjectId();
                    versionsAt.computeIfAbsent(peeled != null ? peeled : ref.getObjectId(), k -> new ArrayList<>()).add(tag);
                }
            }
            List<Ref> lines = new ArrayList<>();
            for (Ref ref : git.branchList().call()) {
                if (ref.getName().startsWith(Constants.R_HEADS + "release/")) {
                    lines.add(ref);
                }
            }
            lines.sort(Comparator.comparing((Ref r) -> lineKey(r.getName())).reversed());
            for (int i = 0; i < lines.size(); i++) {
                Ref ref = lines.get(i);
                String line = ref.getName().substring(Constants.R_HEADS.length());
                boolean recent = i < properties.getKeepReleaseLines();
                if (recent) {
                    kept.add(line);
                }
                RevCommit tip = walk.parseCommit(ref.getObjectId());
                List<String> landingHere = new ArrayList<>();
                for (Workspace space : spaces) {
                    // A landed space with nothing new since has nothing more to land here.
                    boolean done = space.ahead == 0 && !space.dirty
                            && store.landing(WorkspaceId.parse(space.id), line) != null;
                    if (line.equals(space.targetLine) && !done) {
                        landingHere.add(space.id);
                    }
                }
                String when = Instant.ofEpochSecond(tip.getCommitTime()).toString();
                String lineName = line.substring("release/".length());
                if (recent) {
                    // Kept open by default, but anyone may close one that holds nothing unreleased.
                    if (landingHere.isEmpty() && versionsAt.containsKey(tip)) {
                        items.add(new Item(line, "LINE", Action.CLOSE, Reason.FULLY_RELEASED_RECENT, null,
                                lineName, tip.getName(), when, tip.getShortMessage(), versionsAt.get(tip)));
                    }
                } else if (!landingHere.isEmpty()) {
                    items.add(new Item(line, "LINE", Action.BLOCKED, Reason.OPEN_SPACE_LANDS_HERE, null,
                            lineName, tip.getName(), when, tip.getShortMessage(), landingHere));
                } else if (versionsAt.containsKey(tip)) {
                    items.add(new Item(line, "LINE", Action.CLOSE, Reason.FULLY_RELEASED, null,
                            lineName, tip.getName(), when, tip.getShortMessage(), versionsAt.get(tip)));
                } else {
                    items.add(new Item(line, "LINE", Action.BLOCKED, Reason.UNRELEASED_CHANGES, null,
                            lineName, tip.getName(), when, tip.getShortMessage(), new ArrayList<String>()));
                }
            }
        }
        return new Plan(properties.getStaleAfterDays(), properties.getKeepReleaseLines(), items, inUse, kept);
    }

    // --- applying it --------------------------------------------------------------------------------

    /**
     * Applies the chosen items, each named with the head the caller saw. All or nothing: the plan is
     * recomputed under the lock, and any item that moved, changed verdict or is blocked stops the
     * whole run before a single ref moves.
     */
    public List<String> apply(Map<String, String> chosen, String by) throws IOException, GitAPIException {
        return apply(chosen, by, Instant.now());
    }

    List<String> apply(Map<String, String> chosen, String by, Instant now) throws IOException, GitAPIException {
        store.lock().lock();
        // Hold every chosen space exclusively before checking anything: no save or commit can then
        // land between the check below and the removal. Taken in a fixed order (refLock first, then
        // spaces by id), the same order everything else uses, so two tidy-ups cannot deadlock.
        List<java.util.concurrent.locks.Lock> held = new ArrayList<>();
        try {
            for (String id : new java.util.TreeSet<>(chosen.keySet())) {
                WorkspaceId space = WorkspaceId.parse(id);
                if (space != null) {
                    java.util.concurrent.locks.Lock lock = store.spaceLock(space).writeLock();
                    lock.lock();
                    held.add(lock);
                }
            }
            Plan fresh = plan(now);
            Map<String, Item> byId = new LinkedHashMap<>();
            for (Item item : fresh.items) {
                byId.put(item.id, item);
            }
            for (Map.Entry<String, String> entry : chosen.entrySet()) {
                Item item = byId.get(entry.getKey());
                if (item == null || item.action == Action.BLOCKED || !item.head.equals(entry.getValue())) {
                    throw new PlanChanged(entry.getKey() + " changed since the list was shown; nothing was tidied", fresh);
                }
            }

            List<String> done = new ArrayList<>();
            // Archive first: once every archive ref exists, nothing that follows can lose work.
            Map<String, String> archivedAs = new LinkedHashMap<>();
            for (String id : chosen.keySet()) {
                Item item = byId.get(id);
                if (item.action == Action.ARCHIVE) {
                    archivedAs.put(id, archive(item, by));
                }
            }
            for (String id : chosen.keySet()) {
                Item item = byId.get(id);
                if ("SPACE".equals(item.kind)) {
                    store.deleteWorkspace(WorkspaceId.parse(id));
                } else {
                    deleteLine(id, item.head);
                }
                done.add(id);
            }
            return done;
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
            store.lock().unlock();
        }
    }

    private void deleteLine(String line, String expectedHead) throws IOException {
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            RefUpdate update = git.getRepository().updateRef(Constants.R_HEADS + line);
            update.setExpectedOldObjectId(ObjectId.fromString(expectedHead));
            update.setForceUpdate(true);
            RefUpdate.Result result = update.delete();
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.NO_CHANGE) {
                throw new IOException("could not close " + line + ": " + result);
            }
        }
        // Closed here; closed on the remote too, or the next sync would bring it back.
        store.removeFromRemote(Constants.R_HEADS + line);
    }

    /** Keeps a space's newest commit under refs/archive/, in a tag object that records the story. */
    private String archive(Item item, String by) throws IOException {
        WorkspaceId id = WorkspaceId.parse(item.id);
        String[] baseline = store.baselineOf(id);
        Map<String, String> story = new LinkedHashMap<>();
        story.put("branch", item.id);
        story.put("baseline", baseline[0]);
        story.put("targetLine", baseline[1]);
        story.put("reason", item.reason.name());
        story.put("lastActivity", item.lastActivity);
        String ref = ARCHIVE_PREFIX + item.id + "/" + STAMP.format(Instant.now());
        try (Git git = Git.open(store.mainCheckout().toFile()); ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            TagBuilder tag = new TagBuilder();
            tag.setObjectId(ObjectId.fromString(item.head), Constants.OBJ_COMMIT);
            tag.setTag(ref.substring(ARCHIVE_PREFIX.length()));
            tag.setTagger(new PersonIdent(by == null ? properties.getAuthorName() : by, properties.getAuthorEmail()));
            tag.setMessage(JSON.writeValueAsString(story));
            ObjectId tagId = inserter.insert(tag);
            inserter.flush();
            RefUpdate update = git.getRepository().updateRef(ref);
            update.setNewObjectId(tagId);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW) {
                throw new IOException("could not archive " + item.id + ": " + result);
            }
        }
        return ref;
    }

    // --- the archive ---------------------------------------------------------------------------------

    public List<Archived> archived() throws IOException {
        List<Archived> result = new ArrayList<>();
        try (Git git = Git.open(store.mainCheckout().toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            for (Ref ref : git.getRepository().getRefDatabase().getRefsByPrefix(ARCHIVE_PREFIX)) {
                RevObject object = walk.parseAny(ref.getObjectId());
                if (!(object instanceof RevTag)) {
                    continue;
                }
                RevTag tag = (RevTag) object;
                RevCommit commit = walk.parseCommit(tag.getObject());
                @SuppressWarnings("unchecked")
                Map<String, String> story = JSON.readValue(tag.getFullMessage(), Map.class);
                WorkspaceId id = WorkspaceId.parse(story.get("branch"));
                result.add(new Archived(ref.getName(), story.get("branch"), id == null ? null : id.owner,
                        id == null ? story.get("branch") : id.name, commit.getName(), commit.getShortMessage(),
                        tag.getTaggerIdent().getWhenAsInstant().toString(), tag.getTaggerIdent().getName(),
                        story.get("baseline"), story.get("targetLine")));
            }
        }
        result.sort(Comparator.comparing((Archived a) -> a.archivedAt).reversed());
        return result;
    }

    /** Brings an archived space back, as it was; the archive entry goes once the space exists again. */
    public Workspace restore(String archiveRef) throws IOException, GitAPIException {
        Archived entry = null;
        for (Archived a : archived()) {
            if (a.ref.equals(archiveRef)) {
                entry = a;
            }
        }
        if (entry == null) {
            throw new IllegalArgumentException("no such archived space");
        }
        WorkspaceId id = WorkspaceId.parse(entry.branch);
        Workspace restored = store.restoreWorkspace(id, entry.sha, entry.baseline, entry.targetLine);
        try (Git git = Git.open(store.mainCheckout().toFile())) {
            RefUpdate update = git.getRepository().updateRef(archiveRef);
            update.setForceUpdate(true);
            update.delete();
        }
        return restored;
    }

    private static String lineKey(String ref) {
        String[] parts = ref.substring(ref.lastIndexOf('/') + 1).split("\\.");
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append(String.format("%06d", part.matches("\\d+") ? Integer.parseInt(part) : 0));
        }
        return key.toString();
    }

}
