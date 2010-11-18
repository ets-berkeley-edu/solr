package org.sakaiproject.nakamura.api.lite.authorizable;

import java.security.Principal;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.commons.lang.StringUtils;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;

import com.google.common.collect.ImmutableSet;

public class User extends Authorizable {

    public static final String ADMIN_USER = "admin";
    public static final String ANON_USER = "anonymous";
    public static final String SYSTEM_USER = "system";
    private static final String IMPERSONATORS = "impersonators";
    private static final Set<String> DEFAULT_IMPERSONATORS = ImmutableSet.of( ADMIN_USER, ADMINISTRATORS_GROUP);

    public User(Map<String, Object> userMap) {
        super(userMap);
    }

    public boolean isAdmin() {
        return SYSTEM_USER.equals(id) || ADMIN_USER.equals(id) || principals.contains(ADMINISTRATORS_GROUP);
    }

    public boolean allowsImpersonactionBy(Subject impersSubject) {
        String impersonators = StorageClientUtils.toString(getProperty(IMPERSONATORS));
        Set<String> impersonatorSet = DEFAULT_IMPERSONATORS;
        if ( impersonators != null ) {
            impersonatorSet = ImmutableSet.of(StringUtils.split(impersonators,';'));
        }
        for ( Principal p : impersSubject.getPrincipals() ) {
            if ( impersonatorSet.contains(p.getName()) ) {
                return true;
            }
        }
        return false;
    }


}
