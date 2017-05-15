/*
 * This file is part of a module with proprietary Enterprise Features.
 *
 * Licensed to Crate.io Inc. ("Crate.io") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 *
 * To use this file, Crate.io must have given you permission to enable and
 * use such Enterprise Features and you must have a valid Enterprise or
 * Subscription Agreement with Crate.io.  If you enable or use the Enterprise
 * Features, you represent and warrant that you have a valid Enterprise or
 * Subscription Agreement with Crate.io.  Your use of the Enterprise Features
 * if governed by the terms and conditions of your Enterprise or Subscription
 * Agreement with Crate.io.
 */

package io.crate.operation.user;

import com.google.common.collect.ImmutableSet;
import io.crate.action.FutureActionListener;
import io.crate.action.sql.SessionContext;
import io.crate.analyze.*;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.AnalyzedRelationVisitor;
import io.crate.exceptions.UnauthorizedException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.crate.operation.user.UsersMetaData.PROTO;
import static io.crate.operation.user.UsersMetaData.TYPE;

public class UserManagerService implements UserManager, ClusterStateListener {

    static User CRATE_USER = new User("crate", EnumSet.of(User.Role.SUPERUSER));

    static {
        MetaData.registerPrototype(TYPE, PROTO);
    }

    private final TransportCreateUserAction transportCreateUserAction;
    private final TransportDropUserAction transportDropUserAction;
    private final PermissionVisitor permissionVisitor = new PermissionVisitor();
    private volatile Set<User> users = ImmutableSet.of(CRATE_USER);

    public UserManagerService(TransportCreateUserAction transportCreateUserAction,
                              TransportDropUserAction transportDropUserAction,
                              ClusterService clusterService) {
        this.transportCreateUserAction = transportCreateUserAction;
        this.transportDropUserAction = transportDropUserAction;
        clusterService.add(this);
    }

    static Set<User> getUsers(@Nullable UsersMetaData metaData) {
        ImmutableSet.Builder<User> usersBuilder = new ImmutableSet.Builder<User>().add(CRATE_USER);
        if (metaData != null) {
            for (String userName : metaData.users()) {
                usersBuilder.add(new User(userName, ImmutableSet.of()));
            }
        }
        return usersBuilder.build();
    }

    @Override
    public CompletableFuture<Long> createUser(String userName) {
        FutureActionListener<WriteUserResponse, Long> listener = new FutureActionListener<>(r -> 1L);
        transportCreateUserAction.execute(new CreateUserRequest(userName), listener);
        return listener;
    }

    @Override
    public CompletableFuture<Long> dropUser(DropUserAnalyzedStatement analysis) {
        FutureActionListener<WriteUserResponse, Long> listener = new FutureActionListener<>(WriteUserResponse::affectedRows);
        transportDropUserAction.execute(new DropUserRequest(analysis.userName(), analysis.ifExists()), listener);
        return listener;
    }

    public Iterable<User> userGetter() {
        return users;
    }

    @Override
    public void checkPermission(AnalyzedStatement analyzedStatement,
                                   SessionContext sessionContext) {
        permissionVisitor.process(analyzedStatement, sessionContext);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (!event.metaDataChanged()) {
            return;
        }
        users = getUsers(event.state().metaData().custom(UsersMetaData.TYPE));
    }


    @Nullable
    public User findUser(@Nullable String userName) {
        if (userName == null) {
            return null;
        }
        for (User user: userGetter()) {
            if (userName.equals(user.name())) {
                return user;
            }
        }
        return null;
    }

    boolean isSuperUser(@Nullable User user){
        return user != null && user.roles().contains(User.Role.SUPERUSER);
    }

    private class PermissionVisitor extends AnalyzedStatementVisitor<SessionContext, Boolean> {

        private final SelectStatementPermissionVisitor selectVisitor = new SelectStatementPermissionVisitor();

        @Override
        protected Boolean visitAnalyzedStatement(AnalyzedStatement analyzedStatement,
                                                 SessionContext sessionContext) {
            return true;
        }

        @Override
        protected Boolean visitSelectStatement(SelectAnalyzedStatement analysis,
                                               SessionContext sessionContext) {
            if (!selectVisitor.process(analysis.relation(), sessionContext)) {
                throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                    "User \"%s\" is not authorized to execute statement \"%s\"",
                    sessionContext.user() == null ? null: sessionContext.user().name(), analysis));
            }
            return true;
        }

        @Override
        protected Boolean visitCreateUserStatement(CreateUserAnalyzedStatement analysis,
                                                   SessionContext sessionContext) {
            if (!isSuperUser(sessionContext.user())){
                throw new UnauthorizedException(String.format(Locale.ENGLISH, "User \"%s\" is not authorized to execute statement \"%s\"",
                    sessionContext.user() == null ? null : sessionContext.user().name(), analysis));
            }
            return true;
        }

        @Override
        protected Boolean visitDropUserStatement(DropUserAnalyzedStatement analysis,
                                                 SessionContext sessionContext) {
            if (!isSuperUser(sessionContext.user())){
                throw new UnauthorizedException(String.format(Locale.ENGLISH, "User \"%s\" is not authorized to execute statement \"%s\"",
                    sessionContext.user() == null ? null: sessionContext.user().name(), analysis));
            }
            return true;
        }
    }

    private class SelectStatementPermissionVisitor extends AnalyzedRelationVisitor<SessionContext, Boolean> {

        @Override
        protected Boolean visitAnalyzedRelation(AnalyzedRelation relation, SessionContext context) {
            return true;
        }

        @Override
        public Boolean visitQueriedTable(QueriedTable table, SessionContext context) {
            return !(table.tableRelation().tableInfo().ident().name().equals("users") &&
                     table.tableRelation().tableInfo().ident().schema().equals("sys") &&
                     !isSuperUser(context.user()));
        }
    }
}
