package com.shenghui56.dev.kpiperftool.model.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SqlExecResultBO {

    private int index;
    private String sheetName;

    private boolean success;
    private int rowCount;
    private long costMillis;

    private String errorMessage;
}
