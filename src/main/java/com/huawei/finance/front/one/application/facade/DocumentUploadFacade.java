package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import reactor.core.publisher.Mono;

public interface DocumentUploadFacade { Mono<UploadedDocument> upload(DocumentUploadCommand command); }
