package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Which test cases to run. Empty = every enabled test case. Test cases are picked by id, key, folder (id or a path
 * such as "Pricing/Flow 2"), or tag; the test cases they depend on are added automatically.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Selection(List<String> caseIds, List<String> caseKeys, List<String> folderIds, List<String> folderPaths,
                        List<String> tags) {

    public Selection {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        caseKeys = caseKeys == null ? List.of() : List.copyOf(caseKeys);
        folderIds = folderIds == null ? List.of() : List.copyOf(folderIds);
        folderPaths = folderPaths == null ? List.of() : List.copyOf(folderPaths);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static Selection all() {
        return new Selection(null, null, null, null, null);
    }

    public boolean everything() {
        return caseIds.isEmpty() && caseKeys.isEmpty() && folderIds.isEmpty() && folderPaths.isEmpty() && tags.isEmpty();
    }

    public String describe() {
        if (everything()) return "all";
        StringBuilder sb = new StringBuilder();
        if (!caseIds.isEmpty()) sb.append(caseIds.size()).append(" case(s) ");
        if (!caseKeys.isEmpty()) sb.append("cases ").append(String.join(",", caseKeys)).append(' ');
        if (!folderIds.isEmpty()) sb.append(folderIds.size()).append(" folder(s) ");
        if (!folderPaths.isEmpty()) sb.append("folders ").append(String.join(",", folderPaths)).append(' ');
        if (!tags.isEmpty()) sb.append("tags ").append(String.join(",", tags));
        return sb.toString().trim();
    }
}
