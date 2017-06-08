/*
 * 	Copyright (c) 2017. Token Browser, Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tokenbrowser.view.adapter;


import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.tokenbrowser.model.local.ContactThread;
import com.tokenbrowser.model.sofa.SofaMessage;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.sofa.Message;
import com.tokenbrowser.model.sofa.Payment;
import com.tokenbrowser.model.sofa.PaymentRequest;
import com.tokenbrowser.model.sofa.SofaAdapters;
import com.tokenbrowser.model.sofa.SofaType;
import com.tokenbrowser.R;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.adapter.listeners.OnItemClickListener;
import com.tokenbrowser.view.adapter.viewholder.ClickableViewHolder;
import com.tokenbrowser.view.adapter.viewholder.ThreadViewHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RecentAdapter extends RecyclerView.Adapter<ThreadViewHolder> implements ClickableViewHolder.OnClickListener {

    private List<ContactThread> contactThreads;
    private OnItemClickListener<ContactThread> onItemClickListener;

    public RecentAdapter() {
        this.contactThreads = new ArrayList<>(0);
    }

    @Override
    public ThreadViewHolder onCreateViewHolder(final ViewGroup parent, int viewType) {
        final View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item__recent, parent, false);
        return new ThreadViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(final ThreadViewHolder holder, final int position) {
        final ContactThread contactThread = this.contactThreads.get(position);
        holder.setThread(contactThread);

        final String formattedLatestMessage = formatLastMessage(contactThread.getLatestMessage());
        holder.setLatestMessage(formattedLatestMessage);
        holder.setOnClickListener(this);
    }

    @Override
    public int getItemCount() {
        return this.contactThreads.size();
    }

    @Override
    public void onClick(final int position) {
        if (this.onItemClickListener == null) {
            return;
        }

        final ContactThread clickedContactThread = contactThreads.get(position);
        this.onItemClickListener.onItemClick(clickedContactThread);
    }

    public void setContactThreads(final List<ContactThread> contactThreads) {
        this.contactThreads = contactThreads;
        notifyDataSetChanged();
    }

    public RecentAdapter setOnItemClickListener(final OnItemClickListener<ContactThread> onItemClickListener) {
        this.onItemClickListener = onItemClickListener;
        return this;
    }

    public void updateThread(final ContactThread contactThread) {
        final int position = this.contactThreads.indexOf(contactThread);
        if (position == -1) {
            this.contactThreads.add(0, contactThread);
            notifyItemInserted(0);
            return;
        }

        this.contactThreads.set(position, contactThread);
        notifyItemChanged(position);
    }

    private String formatLastMessage(final SofaMessage sofaMessage) {
        final User localUser = getCurrentLocalUser();
        final boolean sentByLocal = sofaMessage.isSentBy(localUser);

        try {
            switch (sofaMessage.getType()) {
                case SofaType.PLAIN_TEXT: {
                    final Message message = SofaAdapters.get().messageFrom(sofaMessage.getPayload());
                    return message.toUserVisibleString(sentByLocal, sofaMessage.hasAttachment());
                }
                case SofaType.PAYMENT: {
                    final Payment payment = SofaAdapters.get().paymentFrom(sofaMessage.getPayload());
                    return payment.toUserVisibleString(sentByLocal, sofaMessage.getSendState());
                }
                case SofaType.PAYMENT_REQUEST: {
                    final PaymentRequest request = SofaAdapters.get().txRequestFrom(sofaMessage.getPayload());
                    return request.toUserVisibleString(sentByLocal, sofaMessage.getSendState());
                }
                case SofaType.COMMAND_REQUEST:
                case SofaType.INIT_REQUEST:
                case SofaType.INIT:
                case SofaType.UNKNOWN:
                    return "";
            }
        } catch (final IOException ex) {
            LogUtil.error(getClass(), "Error parsing SofaMessage. " + ex);
        }

        return "";
    }

    private User getCurrentLocalUser() {
        // Yes, this blocks. But realistically, a value should be always ready for returning.
        return BaseApplication
                .get()
                .getTokenManager()
                .getUserManager()
                .getCurrentUser()
                .toBlocking()
                .value();
    }
}
