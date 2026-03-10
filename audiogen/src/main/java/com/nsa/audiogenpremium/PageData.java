package com.nsa.audiogenpremium;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PageData {
    private int pageNumber;
    private String filePath;
    private int totalWords;
    private List<Map<String, String>> wordsinfo;

    public PageData() {
    }

    public PageData(int pageNumber, String filePath) {
        this.pageNumber = pageNumber;
        this.filePath = filePath;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getTotalWords() {
        return totalWords;
    }

    public List<Map<String, String>> getWordsinfo() {
        return wordsinfo;
    }

    public void setPageNumber(int v) {
        pageNumber = v;
    }

    public void setFilePath(String v) {
        filePath = v;
    }

    public void setTotalWords(int v) {
        totalWords = v;
    }

    public void setWordsinfo(List<Map<String, String>> v) {
        wordsinfo = v;
    }

    public boolean isFullyChecked() {
        if (wordsinfo == null || wordsinfo.isEmpty())
            return false;
        return wordsinfo.stream()
                .allMatch(m -> "true".equalsIgnoreCase(m.getOrDefault("checked", "false")));
    }

    public boolean isTertiaryFullyChecked() {
        if (wordsinfo == null || wordsinfo.isEmpty())
            return false;
        return wordsinfo.stream()
                .allMatch(m -> "true".equalsIgnoreCase(m.getOrDefault("tertiaryChecked", "false")));
    }
}