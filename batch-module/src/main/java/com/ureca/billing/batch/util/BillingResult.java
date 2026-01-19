package com.ureca.billing.batch.util;

import java.util.List;

import com.ureca.billing.core.entity.Bill;
import com.ureca.billing.core.entity.BillDetail;

public class BillingResult {

    private Bill bill;
    private List<BillDetail> billDetail;

    public BillingResult(Bill bill, List<BillDetail> billDetail) {
        this.bill = bill;
        this.billDetail = billDetail;
    }

    public Bill getBill() {
        return bill;
    }

    public List<BillDetail> getBillDetail() {
        return billDetail;
    }
}
