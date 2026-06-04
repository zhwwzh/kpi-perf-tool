package com.shenghui56.dev.kpiperftool.model.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SqlTaskBO {

    private int index;
    private String name;
    private String sql;

    private String sheetName;
}
