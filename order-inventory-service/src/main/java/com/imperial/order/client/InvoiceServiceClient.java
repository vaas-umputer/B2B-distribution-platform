package com.imperial.order.client;

import com.imperial.common.dto.IssueInvoiceRequest;
import com.imperial.common.dto.IssueInvoiceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "invoice-service", url = "${invoice-service.url}")
public interface InvoiceServiceClient {

    @PostMapping("/invoices")
    IssueInvoiceResponse issueInvoice(@RequestBody IssueInvoiceRequest request);
}
