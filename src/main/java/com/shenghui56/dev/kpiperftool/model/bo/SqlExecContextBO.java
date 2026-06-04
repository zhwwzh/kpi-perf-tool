package com.shenghui56.dev.kpiperftool.model.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SqlExecContextBO {

    private boolean allowWrite;

    private int fetchSize;
    private int writeBatchSize;
    private int queryTimeoutSeconds;
}
