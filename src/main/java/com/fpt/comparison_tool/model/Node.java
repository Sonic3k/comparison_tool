package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** An entry of a folder: a sub-folder or a test case, kept in the order they are listed. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Folder.class, name = "folder"),
        @JsonSubTypes.Type(value = TestCase.class, name = "case")
})
public sealed interface Node permits Folder, TestCase {

    String id();

    String name();

    boolean active();
}
