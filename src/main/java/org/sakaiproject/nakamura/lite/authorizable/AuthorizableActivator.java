package org.sakaiproject.nakamura.lite.authorizable;

import java.util.Map;

import org.sakaiproject.nakamura.api.lite.Configuration;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.authorizable.Authorizable;
import org.sakaiproject.nakamura.api.lite.authorizable.User;
import org.sakaiproject.nakamura.lite.storage.StorageClient;
import org.sakaiproject.nakamura.lite.storage.StorageClientException;

import com.google.common.collect.ImmutableMap;

public class AuthorizableActivator {

    private StorageClient client;
    private String keySpace;
    private String authorizableColumnFamily;

    public AuthorizableActivator(StorageClient client, Configuration configuration)
            throws StorageClientException, AccessDeniedException {
        this.client = client;
        this.authorizableColumnFamily = configuration.getAuthorizableColumnFamily();
        this.keySpace = configuration.getKeySpace();
    }

    public synchronized void setup() throws StorageClientException {
        Map<String, Object> authorizableMap = client.get(keySpace, authorizableColumnFamily,
                User.ADMIN_USER);
        if (authorizableMap == null || authorizableMap.size() == 0) {
            createAdminUser();
            createAnonUser();
            createSystemUser();
        }
    }

    private void createSystemUser() throws StorageClientException {
        Map<String, Object> user = ImmutableMap.of(Authorizable.ID_FIELD,
                StorageClientUtils.toStore(User.SYSTEM_USER), Authorizable.NAME_FIELD,
                StorageClientUtils.toStore(User.SYSTEM_USER), Authorizable.PASSWORD_FIELD,
                StorageClientUtils.toStore("--no-password--"));
        System.err.println("Creating SystemUser user as "+User.SYSTEM_USER+" with "+user);
        client.insert(keySpace, authorizableColumnFamily, User.SYSTEM_USER, user);
    }

    private void createAdminUser() throws StorageClientException {
        Map<String, Object> user = ImmutableMap.of(Authorizable.ID_FIELD,
                StorageClientUtils.toStore(User.ADMIN_USER), Authorizable.NAME_FIELD,
                StorageClientUtils.toStore(User.ADMIN_USER), Authorizable.PASSWORD_FIELD,
                StorageClientUtils.toStore(StorageClientUtils.secureHash("admin")));
        System.err.println("Creating Admin user as "+User.ADMIN_USER+" with "+user);
        client.insert(keySpace, authorizableColumnFamily, User.ADMIN_USER, user);
    }

    private void createAnonUser() throws StorageClientException {
        Map<String, Object> user = ImmutableMap.of(Authorizable.ID_FIELD,
                StorageClientUtils.toStore(User.ANON_USER), Authorizable.NAME_FIELD,
                StorageClientUtils.toStore(User.ANON_USER), Authorizable.PASSWORD_FIELD,
                StorageClientUtils.toStore(Authorizable.NO_PASSWORD));
        System.err.println("Creating Anon user as "+User.ADMIN_USER+" with "+user);
        client.insert(keySpace, authorizableColumnFamily, User.ANON_USER, user);
    }

}
