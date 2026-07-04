package top.haohao3001.gfm.webhook;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Model for GitHub push webhook payload.
 */
public class PushEvent {

    private String ref;
    private List<Commit> commits;
    @SerializedName("head_commit")
    private Commit headCommit;

    public String getRef() {
        return ref;
    }

    public String getBranch() {
        if (ref != null && ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        return ref;
    }

    public List<Commit> getCommits() {
        return commits;
    }

    public Commit getHeadCommit() {
        return headCommit;
    }

    public static class Commit {
        private String id;
        private String message;
        private Author author;
        private String timestamp;
        private List<String> added;
        private List<String> removed;
        private List<String> modified;

        public String getId() { return id; }
        public String getMessage() { return message; }
        public Author getAuthor() { return author; }
        public String getTimestamp() { return timestamp; }
        public List<String> getAdded() { return added; }
        public List<String> getRemoved() { return removed; }
        public List<String> getModified() { return modified; }
    }

    public static class Author {
        private String name;
        public String getName() { return name; }
    }
}
