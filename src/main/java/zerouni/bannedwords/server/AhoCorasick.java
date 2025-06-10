package zerouni.bannedwords.server;

import zerouni.bannedwords.BannedWords;
import java.util.*;

/**
 * An implementation of the Aho-Corasick algorithm for efficient multi-pattern string matching.
 * This structure allows finding all occurrences of a set of patterns in a text in a single pass.
 * It's optimized for large pattern sets against potentially shorter text snippets.
 */
public class AhoCorasick {
    private TrieNode root;

    /**
     * Constructs a new Aho-Corasick automaton.
     */
    public AhoCorasick() {
        root = new TrieNode();
    }

    /**
     * Adds a pattern (banned word or phrase) to the automaton's dictionary.
     * Patterns should ideally be added in lowercase for case-insensitive matching later.
     * @param pattern The string pattern to add.
     */
    public void addPattern(String pattern) {
        TrieNode curr = root;
        for (char c : pattern.toCharArray()) {
            curr.children.putIfAbsent(c, new TrieNode());
            curr = curr.children.get(c);
        }
        curr.output.add(pattern);
        BannedWords.LOGGER.info("Added pattern: '{}'", pattern);
    }

    /**
     * Builds the failure links for all nodes in the Trie.
     * This step must be called after all patterns have been added and before any searches.
     */
    public void buildFailureLinks() {
        Queue<TrieNode> queue = new LinkedList<>();

        // Level 1 children (direct children of root) have their failure links point to the root
        for (TrieNode child : root.children.values()) {
            queue.add(child);
            child.failureLink = root;
        }

        while (!queue.isEmpty()) {
            TrieNode curr = queue.poll();

            for (Map.Entry<Character, TrieNode> entry : curr.children.entrySet()) {
                char c = entry.getKey();
                TrieNode child = entry.getValue();
                queue.add(child);

                // Find the longest proper suffix of the current prefix that is also a prefix of some pattern
                TrieNode failureNode = curr.failureLink;
                while (failureNode != null && !failureNode.children.containsKey(c)) {
                    failureNode = failureNode.failureLink;
                }
                if (failureNode == null) {
                    child.failureLink = root;
                } else {
                    child.failureLink = failureNode.children.get(c);
                }
                // Inherit output patterns from the failure link's output
                // This means if "hers" is an output and "s" is a suffix, then "s" node will have "hers" output too
                child.output.addAll(child.failureLink.output);
            }
        }
        BannedWords.LOGGER.info("Aho-Corasick automaton built with failure links.");
    }
    
    /**
     * Finds all occurrences of the stored patterns within the given text.
     * @param text The text to search within. Should be in lowercase if patterns were added in lowercase.
     * @return A list of all detected patterns.
     */
    public List<String> findAll(String text) {
        List<String> matches = new ArrayList<>();
        TrieNode curr = root;
        
        BannedWords.LOGGER.debug("Aho-Corasick: Starting search in text: \"{}\" (length: {})", text, text.length());
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Traverse using failure links until a match is found or root is reached
            while (curr != null && !curr.children.containsKey(c)) {
                curr = curr.failureLink;
            }
            if (curr == null) {
                curr = root;
            } else {
                curr = curr.children.get(c);
            }

            // Collect all output patterns at the current node (and implicitly via failure links)
            if (curr != null && !curr.output.isEmpty()) {
                BannedWords.LOGGER.info("Aho-Corasick: Found matches at position {}: {}", i, curr.output);
                matches.addAll(curr.output);
            }
        }
        
        BannedWords.LOGGER.debug("Aho-Corasick: Search complete. Total matches found: {}", matches.size());
        return matches;
    }

    /**
     * Represents a node in the Aho-Corasick automaton (and underlying Trie).
     */
    private static class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        TrieNode failureLink;
        List<String> output = new ArrayList<>();
    }
}