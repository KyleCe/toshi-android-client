package com.bakkenbaeck.token.presenter.store;


import android.util.Pair;

import com.bakkenbaeck.token.model.local.ChatMessage;
import com.bakkenbaeck.token.model.local.Conversation;
import com.bakkenbaeck.token.model.local.User;

import io.realm.Realm;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import rx.Single;
import rx.subjects.PublishSubject;

public class ConversationStore {
    private final Realm realm;
    private static String watchedConversationId;
    private final static PublishSubject<ChatMessage> newMessageObservable = PublishSubject.create();
    private final static PublishSubject<ChatMessage> updatedMessageObservable = PublishSubject.create();

    public ConversationStore() {
        this.realm = Realm.getDefaultInstance();
    }

    // Returns a pair of RxSubjects, the first being the observable for new messages
    // the second being the observable for updated messages.
    public Pair<PublishSubject<ChatMessage>, PublishSubject<ChatMessage>> registerForChanges(final String conversationId) {
        watchedConversationId = conversationId;
        return new Pair<>(newMessageObservable, updatedMessageObservable);
    }

    public void saveNewMessage(final User user, final ChatMessage message) {

        realm.beginTransaction();
        final Conversation storedConversation = loadWhere("conversationId", user.getOwnerAddress());
        final Conversation conversationToStore = storedConversation == null
                ? new Conversation(user)
                : storedConversation;
        final ChatMessage storedMessage = realm.copyToRealm(message);
        conversationToStore.setLatestMessage(storedMessage);
        realm.copyToRealmOrUpdate(conversationToStore);
        realm.commitTransaction();
        broadcastNewChatMessage(user.getOwnerAddress(), message);
    }

    public Single<RealmResults<Conversation>> loadAll() {
        return Single.fromCallable(() -> {
            final RealmQuery<Conversation> query = realm.where(Conversation.class);
            return query.findAll();
        });
    }

    public Single<Conversation> loadByAddress(final String address) {
        return Single.fromCallable(() -> loadWhere("conversationId", address));
    }

    private Conversation loadWhere(final String fieldName, final String value) {
        final RealmQuery<Conversation> query = realm.where(Conversation.class);
        query.equalTo(fieldName, value);
        return query.findFirst();
    }

    public void updateMessage(final User user, final ChatMessage message) {
        realm.beginTransaction();
        realm.insertOrUpdate(message);
        realm.commitTransaction();
        broadcastUpdatedChatMessage(user.getOwnerAddress(), message);
    }

    private void broadcastNewChatMessage(final String conversationId, final ChatMessage newMessage) {
        if (watchedConversationId == null || !watchedConversationId.equals(conversationId)) {
            return;
        }
        newMessageObservable.onNext(newMessage);
    }

    private void broadcastUpdatedChatMessage(final String conversationId, final ChatMessage updatedMessage) {
        if (watchedConversationId == null || !watchedConversationId.equals(conversationId)) {
            return;
        }
        updatedMessageObservable.onNext(updatedMessage);
    }
}