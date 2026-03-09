
package com.nsa.audiogenpremium;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PageData {
    private int pageNumber;
    private String filePath;
    private List<Map<String, String>> wordsinfo; // [{arabic:"", bangla:""}]

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

    public List<Map<String, String>> getWordsinfo() {
        return wordsinfo;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setWordsinfo(List<Map<String, String>> wordsinfo) {
        this.wordsinfo = wordsinfo;
    }
}