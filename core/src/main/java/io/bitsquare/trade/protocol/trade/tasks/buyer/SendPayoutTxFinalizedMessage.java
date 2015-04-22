/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade.protocol.trade.tasks.buyer;

import io.bitsquare.common.taskrunner.TaskRunner;
import io.bitsquare.p2p.listener.SendMessageListener;
import io.bitsquare.trade.Trade;
import io.bitsquare.trade.protocol.trade.TradeTask;
import io.bitsquare.trade.protocol.trade.messages.PayoutTxFinalizedMessage;
import io.bitsquare.trade.states.BuyerTradeState;
import io.bitsquare.trade.states.StateUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SendPayoutTxFinalizedMessage extends TradeTask {
    private static final Logger log = LoggerFactory.getLogger(SendPayoutTxFinalizedMessage.class);

    public SendPayoutTxFinalizedMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            PayoutTxFinalizedMessage tradeMessage = new PayoutTxFinalizedMessage(processModel.getId(), trade.getPayoutTx());
            processModel.getMessageService().sendEncryptedMessage(
                    trade.getTradingPeer(),
                    processModel.tradingPeer.getPubKeyRing(),
                    tradeMessage,
                    new SendMessageListener() {
                        @Override
                        public void handleResult() {
                            log.trace("PayoutTxFinalizedMessage successfully arrived at peer");

                            trade.setProcessState(BuyerTradeState.ProcessState.PAYOUT_TX_SENT);

                            complete();
                        }

                        @Override
                        public void handleFault() {
                            appendToErrorMessage("Sending PayoutTxFinalizedMessage failed");
                            trade.setErrorMessage(errorMessage);
                            StateUtil.setSendFailedState(trade);
                            failed();
                        }
                    });
        } catch (Throwable t) {
            t.printStackTrace();
            trade.setThrowable(t);
            failed(t);
        }
    }
}