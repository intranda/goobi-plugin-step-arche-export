package org.goobi.api.rest;

import lombok.Getter;
import lombok.Setter;

public class TransactionInfo {

    /**
     * Transaction id
     **/
    private Long transactionId;

    /**
     * Transaction start date and time
     **/
    private String startedAt;

    /**
     * Transaction last request date and time
     **/
    private String lastRequest;

    /**
     * Transaction's current state
     **/
    private String state;

    @Getter
    @Setter
    private Integer lockedResourceCount;

    /**
     * Transaction id
     *
     * @return transactionId
     **/
    public Long getTransactionId() {
        return transactionId;
    }

    /**
     * Set transactionId
     **/
    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public TransactionInfo transactionId(Long transactionId) {
        this.transactionId = transactionId;
        return this;
    }

    /**
     * Transaction start date and time
     *
     * @return startedAt
     **/
    public String getStartedAt() {
        return startedAt;
    }

    /**
     * Set startedAt
     **/
    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public TransactionInfo startedAt(String startedAt) {
        this.startedAt = startedAt;
        return this;
    }

    /**
     * Transaction last request date and time
     *
     * @return lastRequest
     **/
    public String getLastRequest() {
        return lastRequest;
    }

    /**
     * Set lastRequest
     **/
    public void setLastRequest(String lastRequest) {
        this.lastRequest = lastRequest;
    }

    public TransactionInfo lastRequest(String lastRequest) {
        this.lastRequest = lastRequest;
        return this;
    }

    /**
     * Transaction&#39;s current state
     *
     * @return state
     **/
    public String getState() {
        return state;
    }

    /**
     * Set state
     **/
    public void setState(String state) {
        this.state = state;
    }

    public TransactionInfo state(String state) {
        this.state = state;
        return this;
    }

    /**
     * Create a string representation of this pojo.
     **/
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class TransactionInfo {\n");
        sb.append("    transactionId: ").append(toIndentedString(transactionId)).append("\n");
        sb.append("    startedAt: ").append(toIndentedString(startedAt)).append("\n");
        sb.append("    lastRequest: ").append(toIndentedString(lastRequest)).append("\n");
        sb.append("    state: ").append(toIndentedString(state)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert the given object to string with each line indented by 4 spaces (except the first line).
     */
    private static String toIndentedString(Object o) {
        if (o == null) {
            return "null";
        }
        return o.toString().replace("\n", "\n    ");
    }
}
