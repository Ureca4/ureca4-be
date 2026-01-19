package com.ureca.billing.batch.util;

import java.util.List;

import com.ureca.billing.core.entity.Bill;
import com.ureca.billing.core.entity.BillDetail;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BillingResult {

    private final Bill bill;
    private final List<BillDetail> billDetail;

    // 메타 데이터
    private final String planName;
    private final String emailCipher;
    private final String phoneCipher;

    // 금액 정보 (Long)
    private final long planFee;
    private final long addonFee;
    private final long microPaymentFee;
}
