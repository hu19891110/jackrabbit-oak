/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.authorization;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.jcr.AccessDeniedException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.security.authorization.PrivilegeManager;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.oak.TestNameMapper;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.namepath.NameMapper;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.namepath.NamePathMapperImpl;
import org.apache.jackrabbit.oak.plugins.name.Namespaces;
import org.apache.jackrabbit.oak.plugins.value.ValueFactoryImpl;
import org.apache.jackrabbit.oak.security.privilege.PrivilegeBitsProvider;
import org.apache.jackrabbit.oak.security.privilege.PrivilegeConstants;
import org.apache.jackrabbit.oak.spi.security.authorization.AbstractAccessControlTest;
import org.apache.jackrabbit.oak.spi.security.authorization.restriction.RestrictionProvider;
import org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal;
import org.apache.jackrabbit.oak.util.NodeUtil;
import org.apache.jackrabbit.oak.util.TreeUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the default {@code AccessControlManager} implementation.
 */
public class AccessControlManagerImplTest extends AbstractAccessControlTest implements AccessControlConstants {

    private final String testName = TestNameMapper.TEST_PREFIX + ":testRoot";
    private final String testPath = '/' + testName;

    private Principal testPrincipal;
    private Privilege[] testPrivileges;
    private Root testRoot;

    private TestNameMapper nameMapper;
    private NamePathMapper npMapper;

    private AccessControlManagerImpl acMgr;
    private ValueFactory valueFactory;

    @Override
    @Before
    public void before() throws Exception {
        super.before();

        registerNamespace(TestNameMapper.TEST_PREFIX, TestNameMapper.TEST_URI);
        nameMapper = new TestNameMapper(Namespaces.getNamespaceMap(root.getTree("/")));
        npMapper = new NamePathMapperImpl(nameMapper);

        acMgr = getAccessControlManager(npMapper);
        valueFactory = new ValueFactoryImpl(root.getBlobFactory(), npMapper);

        NodeUtil rootNode = new NodeUtil(root.getTree("/"), npMapper);
        rootNode.addChild(testName, JcrConstants.NT_UNSTRUCTURED);
        root.commit();

        testPrivileges = privilegesFromNames(Privilege.JCR_ADD_CHILD_NODES, Privilege.JCR_READ);
        testPrincipal = getTestPrincipal();
    }

    @After
    public void after() throws Exception {
        try {
            root.refresh();
            root.getTree(testPath).remove();
            root.commit();

            if (testRoot != null) {
                testRoot.getContentSession().close();
                testRoot = null;
            }
        } finally {
            super.after();
        }
    }

    @Override
    protected NamePathMapper getNamePathMapper() {
        return npMapper;
    }

    private AccessControlManagerImpl getAccessControlManager(NamePathMapper npMapper) {
        return new AccessControlManagerImpl(root, npMapper, getSecurityProvider());
    }

    private Root getTestRoot() throws Exception {
        if (testRoot == null) {
            testRoot = createTestSession().getLatestRoot();
        }
        return testRoot;
    }

    private AccessControlManagerImpl getTestAccessControlManager() throws Exception {
        return new AccessControlManagerImpl(getTestRoot(), getNamePathMapper(), getSecurityProvider());
    }

    private NamePathMapper getLocalNamePathMapper() {
        NameMapper remapped = new TestNameMapper(nameMapper, TestNameMapper.LOCAL_MAPPING);
        return new NamePathMapperImpl(remapped);
    }

    private ACL getApplicablePolicy(String path) throws RepositoryException {
        AccessControlPolicyIterator itr = acMgr.getApplicablePolicies(path);
        if (itr.hasNext()) {
            return (ACL) itr.nextAccessControlPolicy();
        } else {
            throw new RepositoryException("No applicable policy found.");
        }
    }

    private ACL createPolicy(String path) {
        final PrincipalManager pm = getPrincipalManager();
        final RestrictionProvider rp = getRestrictionProvider();
        return new ACL(path, getNamePathMapper()) {
            @Override
            PrincipalManager getPrincipalManager() {
                return pm;
            }

            @Override
            PrivilegeManager getPrivilegeManager() {
                return AccessControlManagerImplTest.this.getPrivilegeManager();
            }

            @Override
            PrivilegeBitsProvider getPrivilegeBitsProvider() {
                return new PrivilegeBitsProvider(root);
            }

            @Nonnull
            @Override
            public RestrictionProvider getRestrictionProvider() {
                return rp;
            }
        };
    }

    private void setupPolicy(String path) throws RepositoryException {
        ACL policy = getApplicablePolicy(path);
        if (path == null) {
            policy.addAccessControlEntry(testPrincipal, testPrivileges);
        } else {
            policy.addEntry(testPrincipal, testPrivileges, true, getGlobRestriction("*"));
        }
        acMgr.setPolicy(path, policy);
    }

    private Map<String, Value> getGlobRestriction(String value) {
        return ImmutableMap.of(REP_GLOB, valueFactory.createValue(value));
    }

    private List<String> getInvalidPaths() {
        List<String> invalid = new ArrayList<String>();
        invalid.add("");
        invalid.add("../../jcr:testRoot");
        invalid.add("jcr:testRoot");
        invalid.add("jcr:test/Root");
        invalid.add("./jcr:testRoot");
        return invalid;
    }

    private List<String> getAcContentPaths() throws RepositoryException {
        ACL policy = getApplicablePolicy(testPath);
        policy.addEntry(testPrincipal, testPrivileges, true, getGlobRestriction("*"));
        acMgr.setPolicy(testPath, policy);

        String aclPath = testPath + '/' + REP_POLICY;
        Tree acl = root.getTree(aclPath);
        assertNotNull(acl);
        Iterator<Tree> aces = acl.getChildren().iterator();
        assertTrue(aces.hasNext());
        Tree ace = aces.next();
        assertNotNull(ace);

        List<String> acContentPath = new ArrayList<String>();
        acContentPath.add(aclPath);
        acContentPath.add(ace.getPath());

        Tree rest = ace.getChild(REP_RESTRICTIONS);
        if (rest != null) {
            acContentPath.add(rest.getPath());
        }
        return acContentPath;
    }

    private Set<Principal> getPrincipals(ContentSession session) {
        return session.getAuthInfo().getPrincipals();
    }

    //---------------------------------------------< getSupportedPrivileges >---
    @Test
    public void testGetSupportedPrivileges() throws Exception {
        List<Privilege> allPrivileges = Arrays.asList(getPrivilegeManager().getRegisteredPrivileges());

        List<String> testPaths = new ArrayList<String>();
        testPaths.add(null);
        testPaths.add("/");
        testPaths.add("/jcr:system");
        testPaths.add(testPath);

        for (String path : testPaths) {
            Privilege[] supported = acMgr.getSupportedPrivileges(path);

            assertNotNull(supported);
            assertEquals(allPrivileges.size(), supported.length);
            assertTrue(allPrivileges.containsAll(Arrays.asList(supported)));
        }
    }

    @Test
    public void testGetSupportedPrivilegesInvalidPath() throws Exception {
        for (String path : getInvalidPaths()) {
            try {
                acMgr.getSupportedPrivileges(path);
                fail("Expects valid node path, found: " + path);
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testGetSupportedPrivilegesPropertyPath() throws Exception {
        try {
            acMgr.getSupportedPrivileges("/jcr:primaryType");
            fail("Property path -> PathNotFoundException expected.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetSupportedPrivilegesNonExistingPath() throws Exception {
        try {
            acMgr.getSupportedPrivileges("/non/existing/node");
            fail("Nonexisting node -> PathNotFoundException expected.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetSupportedPrivilegesIncludingPathConversion() throws Exception {
        List<Privilege> allPrivileges = Arrays.asList(getPrivilegeManager().getRegisteredPrivileges());

        List<String> testPaths = new ArrayList<String>();
        testPaths.add('/' + TestNameMapper.TEST_LOCAL_PREFIX + ":testRoot");
        testPaths.add("/{" + TestNameMapper.TEST_URI + "}testRoot");

        AccessControlManager acMgr = getAccessControlManager(getLocalNamePathMapper());
        for (String path : testPaths) {
            Privilege[] supported = acMgr.getSupportedPrivileges(path);

            assertNotNull(supported);
            assertEquals(allPrivileges.size(), supported.length);
            assertTrue(allPrivileges.containsAll(Arrays.asList(supported)));
        }
    }

    //--------------------------------------------------< privilegeFromName >---
    @Test
    public void testPrivilegeFromName() throws Exception {
        List<Privilege> allPrivileges = Arrays.asList(getPrivilegeManager().getRegisteredPrivileges());
        for (Privilege privilege : allPrivileges) {
            Privilege p = acMgr.privilegeFromName(privilege.getName());
            assertEquals(privilege, p);
        }
    }

    @Test
    public void testPrivilegeFromExpandedName() throws Exception {
        Privilege readPriv = getPrivilegeManager().getPrivilege(PrivilegeConstants.JCR_READ);
        assertEquals(readPriv, acMgr.privilegeFromName(Privilege.JCR_READ));
    }

    @Test
    public void testPrivilegeFromInvalidName() throws Exception {
        List<String> invalid = new ArrayList<String>();
        invalid.add(null);
        invalid.add("");
        invalid.add("test:read");

        for (String privilegeName : invalid) {
            try {
                acMgr.privilegeFromName(privilegeName);
                fail("Invalid privilege name " + privilegeName);
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testPrivilegeFromUnknownName() throws Exception {
        List<String> invalid = new ArrayList<String>();
        invalid.add("unknownPrivilege");
        invalid.add('{' + NamespaceRegistry.NAMESPACE_JCR + "}unknown");

        for (String privilegeName : invalid) {
            try {
                acMgr.privilegeFromName(privilegeName);
                fail("Invalid privilege name " + privilegeName);
            } catch (AccessControlException e) {
                // success
            }
        }
    }

    //------------------------------------------------------< hasPrivileges >---
    @Test
    public void testHasNullPrivileges() throws Exception {
        assertTrue(acMgr.hasPrivileges(testPath, null));
    }

    @Test
    public void testHasEmptyPrivileges() throws Exception {
        assertTrue(acMgr.hasPrivileges(testPath, new Privilege[0]));
    }

    @Test
    public void testHasPrivilegesForPropertyPath() throws Exception {
        String propertyPath = "/jcr:primaryType";
        Privilege[] privs = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        try {
            acMgr.hasPrivileges(propertyPath, privs);
            fail("AccessControlManager#hasPrivileges for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }

        try {
            acMgr.hasPrivileges(propertyPath, getPrincipals(adminSession), privs);
            fail("AccessControlManager#hasPrivileges for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testHasPrivilegesNonExistingNodePath() throws Exception {
        String nonExistingPath = "/not/existing";
        Privilege[] privs = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        try {
            acMgr.hasPrivileges(nonExistingPath, privs);
            fail("AccessControlManager#hasPrivileges  for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
        try {
            acMgr.hasPrivileges(nonExistingPath, getPrincipals(adminSession), privs);
            fail("AccessControlManager#hasPrivileges  for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testHasPrivilegesInvalidPaths() throws Exception {
        Privilege[] privs = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        for (String path : getInvalidPaths()) {
            try {
                acMgr.hasPrivileges(path, privs);
                fail("AccessControlManager#hasPrivileges  for node that doesn't exist should fail.");
            } catch (RepositoryException e) {
                // success
            }
        }
        for (String path : getInvalidPaths()) {
            try {
                acMgr.hasPrivileges(path, getPrincipals(adminSession), privs);
                fail("AccessControlManager#hasPrivileges  for node that doesn't exist should fail.");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testHasPrivilegesAccessControlledNodePath() throws Exception {
        Privilege[] privs = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        for (String path : getAcContentPaths()) {
            assertTrue(acMgr.hasPrivileges(path, privs));
            assertTrue(acMgr.hasPrivileges(path, getPrincipals(adminSession), privs));
        }
    }

    /**
     * @since OAK 1.0 As of OAK AccessControlManager#hasPrivilege will throw
     * PathNotFoundException in case the node associated with a given path is
     * not readable to the editing session.
     */
    @Test
    public void testHasPrivilegesNotAccessiblePath() throws Exception {
        List<String> notAccessible = new ArrayList();
        notAccessible.add("/");
        notAccessible.addAll(getAcContentPaths());

        Privilege[] privs = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        AccessControlManagerImpl testAcMgr = getTestAccessControlManager();
        for (String path : notAccessible) {
            try {
                testAcMgr.hasPrivileges(path, privs);
                fail("AccessControlManager#hasPrivileges for node that is not accessible should fail.");
            } catch (PathNotFoundException e) {
                // success
            }
        }
        for (String path : notAccessible) {
            try {
                testAcMgr.hasPrivileges(path, getPrincipals(getTestRoot().getContentSession()), privs);
                fail("AccessControlManager#hasPrivileges for node that is not accessible should fail.");
            } catch (PathNotFoundException e) {
                // success
            }
        }
    }

    @Test
    public void testTestSessionHasPrivileges() throws Exception {
        setupPolicy(testPath);
        root.commit();

        AccessControlManagerImpl testAcMgr = getTestAccessControlManager();

        // granted privileges
        List<Privilege[]> granted = new ArrayList<Privilege[]>();
        granted.add(privilegesFromNames(PrivilegeConstants.JCR_READ));
        granted.add(privilegesFromNames(PrivilegeConstants.REP_READ_NODES));
        granted.add(privilegesFromNames(PrivilegeConstants.REP_READ_PROPERTIES));
        granted.add(privilegesFromNames(PrivilegeConstants.JCR_ADD_CHILD_NODES));
        granted.add(testPrivileges);

        for (Privilege[] privileges : granted) {
            assertTrue(testAcMgr.hasPrivileges(testPath, privileges));
            assertTrue(testAcMgr.hasPrivileges(testPath, getPrincipals(getTestRoot().getContentSession()), privileges));
        }

        // denied privileges
        List<Privilege[]> denied = new ArrayList<Privilege[]>();
        denied.add(privilegesFromNames(PrivilegeConstants.JCR_ALL));
        denied.add(privilegesFromNames(PrivilegeConstants.JCR_READ_ACCESS_CONTROL));
        denied.add(privilegesFromNames(PrivilegeConstants.JCR_WRITE));
        denied.add(privilegesFromNames(PrivilegeConstants.JCR_LOCK_MANAGEMENT));

        for (Privilege[] privileges : denied) {
            assertFalse(testAcMgr.hasPrivileges(testPath, privileges));
            assertFalse(testAcMgr.hasPrivileges(testPath, getPrincipals(getTestRoot().getContentSession()), privileges));
        }
    }

    @Test
    public void testTestSessionHasPrivilegesForPrincipals() throws Exception {
        setupPolicy(testPath);
        root.commit();

        AccessControlManagerImpl testAcMgr = getTestAccessControlManager();
        // but for 'admin' the test-session doesn't have sufficient privileges
        try {
            testAcMgr.getPrivileges(testPath, getPrincipals(adminSession));
            fail("testSession doesn't have sufficient permission to read access control information at testPath");
        } catch (AccessDeniedException e) {
            // success
        }
    }

    @Test
    public void testHasRepoPrivileges() throws Exception {
        assertTrue(acMgr.hasPrivileges(null, privilegesFromNames(PrivilegeConstants.JCR_ALL)));
        assertTrue(acMgr.hasPrivileges(null, getPrincipals(adminSession), privilegesFromNames(PrivilegeConstants.JCR_ALL)));
    }

    @Test
    public void testTestSessionHasRepoPrivileges() throws Exception {
        AccessControlManagerImpl testAcMgr = getTestAccessControlManager();

        assertFalse(testAcMgr.hasPrivileges(null, testPrivileges));
        assertFalse(testAcMgr.hasPrivileges(null, getPrincipals(getTestRoot().getContentSession()), testPrivileges));

        // but for 'admin' the test-session doesn't have sufficient privileges
        try {
            testAcMgr.getPrivileges(null, getPrincipals(adminSession));
            fail("testSession doesn't have sufficient permission to read access control information");
        } catch (AccessDeniedException e) {
            // success
        }
    }

    //------------------------------------------------------< getPrivileges >---
    @Test
    public void testGetPrivilegesForPropertyPath() throws Exception {
        String propertyPath = "/jcr:primaryType";
        try {
            acMgr.getPrivileges(propertyPath);
            fail("AccessControlManager#getPrivileges for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }

        try {
            acMgr.getPrivileges(propertyPath, Collections.singleton(testPrincipal));
            fail("AccessControlManager#getPrivileges for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetPrivilegesNonExistingNodePath() throws Exception {
        String nonExistingPath = "/not/existing";
        try {
            acMgr.getPrivileges(nonExistingPath);
            fail("AccessControlManager#getPrivileges  for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }

        try {
            acMgr.getPrivileges(nonExistingPath, Collections.singleton(testPrincipal));
            fail("AccessControlManager#getPrivileges  for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetPrivilegesInvalidPaths() throws Exception {
        for (String path : getInvalidPaths()) {
            try {
                acMgr.getPrivileges(path);
                fail("AccessControlManager#getPrivileges  for node that doesn't exist should fail.");
            } catch (RepositoryException e) {
                // success
            }
        }

        for (String path : getInvalidPaths()) {
            try {
                acMgr.getPrivileges(path, Collections.singleton(testPrincipal));
                fail("AccessControlManager#getPrivileges  for node that doesn't exist should fail.");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testGetPrivilegesAccessControlledNodePath() throws Exception {
        Privilege[] expected = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        for (String path : getAcContentPaths()) {
            assertArrayEquals(expected, acMgr.getPrivileges(path));
            assertArrayEquals(expected, acMgr.getPrivileges(path, getPrincipals(adminSession)));
        }
    }

    /**
     * @since OAK 1.0 As of OAK AccessControlManager#hasPrivilege will throw
     * PathNotFoundException in case the node associated with a given path is
     * not readable to the editing session.
     */
    @Test
    public void testGetPrivilegesNotAccessiblePath() throws Exception {
        List<String> notAccessible = new ArrayList();
        notAccessible.add("/");
        notAccessible.addAll(getAcContentPaths());

        for (String path : notAccessible) {
            try {
                getTestAccessControlManager().getPrivileges(path);
                fail("AccessControlManager#getPrivileges for node that is not accessible should fail.");
            } catch (PathNotFoundException e) {
                // success
            }
        }

        for (String path : notAccessible) {
            try {
                getTestAccessControlManager().getPrivileges(path, Collections.singleton(testPrincipal));
                fail("AccessControlManager#getPrivileges for node that is not accessible should fail.");
            } catch (PathNotFoundException e) {
                // success
            }
        }

    }

    /**
     * // TODO review again
     * @since OAK 1.0 : access to privileges needs read access to the corresponding tree.
     */
    @Test
    public void testTestSessionGetPrivileges() throws Exception {
        setupPolicy(testPath);
        root.commit();

        AccessControlManagerImpl testAcMgr = getTestAccessControlManager();
        Set<Principal> testPrincipals = getPrincipals(getTestRoot().getContentSession());

        // TODO: check again...
        try {
            testAcMgr.getPrivileges(testPath);
            fail("no read access to the privilege store.");
        } catch (AccessControlException e) {
            // success
        }
        try {
            getTestAccessControlManager().getPrivileges(testPath, testPrincipals);
            fail("no read access to the privilege store.");
        } catch (AccessControlException e) {
            // success
        }

        // ensure readability of the privileges
        try {
            setupPolicy("/jcr:system");
            root.commit();

            getTestRoot().refresh();

            assertArrayEquals(new Privilege[0], testAcMgr.getPrivileges(null));
            assertArrayEquals(new Privilege[0], testAcMgr.getPrivileges(null, testPrincipals));

            Privilege[] privs = testAcMgr.getPrivileges(testPath);
            assertEquals(ImmutableSet.copyOf(testPrivileges), ImmutableSet.copyOf(privs));

            privs = testAcMgr.getPrivileges(testPath, testPrincipals);
            assertEquals(ImmutableSet.copyOf(testPrivileges), ImmutableSet.copyOf(privs));

        } finally {
            for (AccessControlPolicy policy : acMgr.getPolicies("/jcr:system")) {
                acMgr.removePolicy("/jcr:system", policy);
            }
            root.commit();
        }

        // but for 'admin' the test-session doesn't have sufficient privileges
        try {
            testAcMgr.getPrivileges(testPath, getPrincipals(adminSession));
            fail("testSession doesn't have sufficient permission to read access control information at testPath");
        } catch (AccessDeniedException e) {
            // success
        }
    }

    @Test
    public void testGetRepoPrivileges() throws Exception {
        assertArrayEquals(privilegesFromNames(PrivilegeConstants.JCR_ALL), acMgr.getPrivileges(null));
    }

    @Test
    public void testGetPrivilegesForPrincipals() throws Exception {
        Set<Principal> adminPrincipals = getPrincipals(adminSession);
        Privilege[] expected = privilegesFromNames(PrivilegeConstants.JCR_ALL);
        assertArrayEquals(expected, acMgr.getPrivileges("/", adminPrincipals));
        assertArrayEquals(expected, acMgr.getPrivileges(null, adminPrincipals));
        assertArrayEquals(expected, acMgr.getPrivileges(testPath, adminPrincipals));

        setupPolicy(testPath);
        root.commit();
        Set<Principal> testPrincipals = Collections.singleton(testPrincipal);
        assertArrayEquals(new Privilege[0], acMgr.getPrivileges(null, testPrincipals));
        assertArrayEquals(new Privilege[0], acMgr.getPrivileges("/", testPrincipals));
        assertEquals(
                ImmutableSet.copyOf(testPrivileges),
                ImmutableSet.copyOf(acMgr.getPrivileges(testPath, testPrincipals)));
    }

    //--------------------------------------< getApplicablePolicies(String) >---

    @Test
    public void testGetApplicablePolicies() throws Exception {
        AccessControlPolicyIterator itr = acMgr.getApplicablePolicies(testPath);

        assertNotNull(itr);
        assertTrue(itr.hasNext());

        AccessControlPolicy policy = itr.nextAccessControlPolicy();
        assertNotNull(policy);
        assertTrue(policy instanceof ACL);

        ACL acl = (ACL) policy;
        assertTrue(acl.isEmpty());
        assertEquals(testPath, acl.getPath());

        assertFalse(itr.hasNext());
    }

    @Test
    public void testGetApplicablePoliciesOnAccessControllable() throws Exception {
        NodeUtil node = new NodeUtil(root.getTree(testPath));
        node.setNames(JcrConstants.JCR_MIXINTYPES, MIX_REP_ACCESS_CONTROLLABLE);

        AccessControlPolicyIterator itr = acMgr.getApplicablePolicies(testPath);

        assertNotNull(itr);
        assertTrue(itr.hasNext());
    }

    @Test
    public void testGetApplicableRepoPolicies() throws Exception {
        AccessControlPolicyIterator itr = acMgr.getApplicablePolicies((String) null);

        assertNotNull(itr);
        assertTrue(itr.hasNext());

        AccessControlPolicy policy = itr.nextAccessControlPolicy();
        assertNotNull(policy);
        assertTrue(policy instanceof ACL);

        ACL acl = (ACL) policy;
        assertTrue(acl.isEmpty());
        assertNull(acl.getPath());

        assertFalse(itr.hasNext());
    }

    @Test
    public void testGetApplicablePoliciesWithCollidingNode() throws Exception {
        NodeUtil testTree = new NodeUtil(root.getTree(testPath));
        testTree.addChild(REP_POLICY, JcrConstants.NT_UNSTRUCTURED);

        AccessControlPolicyIterator itr = acMgr.getApplicablePolicies(testPath);
        assertNotNull(itr);
        assertFalse(itr.hasNext());
    }

    @Test
    public void testGetApplicablePoliciesForAccessControlled() throws Exception {
        AccessControlPolicy policy = getApplicablePolicy(testPath);
        acMgr.setPolicy(testPath, policy);

        AccessControlPolicyIterator itr = acMgr.getApplicablePolicies(testPath);
        assertNotNull(itr);
        assertFalse(itr.hasNext());
    }

    @Test
    public void testGetApplicablePoliciesInvalidPath() throws Exception {
        for (String invalid : getInvalidPaths()) {
            try {
                acMgr.getPolicies(invalid);
                fail("Getting applicable policies for an invalid path should fail");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testApplicablePoliciesForPropertyPath() throws Exception {
        String propertyPath = "/jcr:primaryType";
        try {
            acMgr.getApplicablePolicies(propertyPath);
            fail("Getting applicable policies for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetApplicablePoliciesNonExistingNodePath() throws Exception {
        String nonExistingPath = "/not/existing";
        try {
            acMgr.getApplicablePolicies(nonExistingPath);
            fail("Getting applicable policies for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetApplicablePoliciesForAcContentPaths() throws Exception {
        for (String path : getAcContentPaths()) {
            try {
                acMgr.getApplicablePolicies(path);
                fail("Getting applicable policies for access control content should fail.");
            } catch (AccessControlException e) {
                // success
            }
        }
    }

    //------------------------------------------------< getPolicies(String) >---
    @Test
    public void testGetPolicies() throws Exception {
        AccessControlPolicy policy = getApplicablePolicy(testPath);
        acMgr.setPolicy(testPath, policy);

        AccessControlPolicy[] policies = acMgr.getPolicies(testPath);
        assertNotNull(policies);
        assertEquals(1, policies.length);

        assertTrue(policies[0] instanceof ACL);
        ACL acl = (ACL) policies[0];
        assertTrue(acl.isEmpty());
        assertEquals(testPath, acl.getOakPath());
    }

    @Test
    public void testGetPoliciesNodeNotAccessControlled() throws Exception {
        AccessControlPolicy[] policies = acMgr.getPolicies(testPath);
        assertNotNull(policies);
        assertEquals(0, policies.length);
    }

    @Test
    public void testGetPoliciesAfterSet() throws Exception {
        setupPolicy(testPath);

        AccessControlPolicy[] policies = acMgr.getPolicies(testPath);
        assertNotNull(policies);
        assertEquals(1, policies.length);

        assertTrue(policies[0] instanceof ACL);
        ACL acl = (ACL) policies[0];
        assertFalse(acl.isEmpty());
    }

    @Test
    public void testGetPoliciesAfterRemove() throws Exception {
        setupPolicy(testPath);

        AccessControlPolicy[] policies = acMgr.getPolicies(testPath);
        assertNotNull(policies);
        assertEquals(1, policies.length);

        acMgr.removePolicy(testPath, policies[0]);

        policies = acMgr.getPolicies(testPath);
        assertNotNull(policies);
        assertEquals(0, policies.length);
        assertTrue(acMgr.getApplicablePolicies(testPath).hasNext());
    }

    @Test
    public void testGetPolicyWithInvalidPrincipal() throws Exception {
        ACL policy = getApplicablePolicy(testPath);
        policy.addEntry(testPrincipal, testPrivileges, true, getGlobRestriction("*"));
        acMgr.setPolicy(testPath, policy);

        NodeUtil aclNode = new NodeUtil(root.getTree(testPath + '/' + REP_POLICY));
        NodeUtil aceNode = aclNode.addChild("testACE", NT_REP_DENY_ACE);
        aceNode.setString(REP_PRINCIPAL_NAME, "invalidPrincipal");
        aceNode.setNames(REP_PRIVILEGES, PrivilegeConstants.JCR_READ);

        // reading policies with unknown principal name should not fail.
        AccessControlPolicy[] policies = acMgr.getPolicies(testPath);
        assertNotNull(policies);
        assertEquals(1, policies.length);

        ACL acl = (ACL) policies[0];
        List<String> principalNames = new ArrayList<String>();
        for (AccessControlEntry ace : acl.getEntries()) {
            principalNames.add(ace.getPrincipal().getName());
        }
        assertTrue(principalNames.remove("invalidPrincipal"));
        assertTrue(principalNames.remove(testPrincipal.getName()));
        assertTrue(principalNames.isEmpty());
    }

    @Test
    public void testGetRepoPolicies() throws Exception {
        String path = null;

        AccessControlPolicy[] policies = acMgr.getPolicies(path);
        assertNotNull(policies);
        assertEquals(0, policies.length);

        acMgr.setPolicy(null, acMgr.getApplicablePolicies(path).nextAccessControlPolicy());
        assertFalse(acMgr.getApplicablePolicies(path).hasNext());

        policies = acMgr.getPolicies(path);
        assertNotNull(policies);
        assertEquals(1, policies.length);

        assertTrue(policies[0] instanceof ACL);
        ACL acl = (ACL) policies[0];
        assertTrue(acl.isEmpty());
        assertNull(acl.getPath());
        assertNull(acl.getOakPath());
        assertFalse(acMgr.getApplicablePolicies(path).hasNext());

        acMgr.removePolicy(path, acl);
        assertEquals(0, acMgr.getPolicies(path).length);
        assertTrue(acMgr.getApplicablePolicies(path).hasNext());
    }

    @Test
    public void testGetPoliciesInvalidPath() throws Exception {
        for (String invalid : getInvalidPaths()) {
            try {
                acMgr.getPolicies(invalid);
                fail("Getting policies for an invalid path should fail");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testGetPoliciesPropertyPath() throws Exception {
        String propertyPath = "/jcr:primaryType";
        try {
            acMgr.getPolicies(propertyPath);
            fail("Getting policies for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetPoliciesNonExistingNodePath() throws Exception {
        String nonExistingPath = "/not/existing";
        try {
            acMgr.getPolicies(nonExistingPath);
            fail("Getting policies for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetPoliciesAcContentPaths() throws Exception {
        for (String path : getAcContentPaths()) {
            try {
                acMgr.getPolicies(path);
                fail("Getting policies for access control content should fail.");
            } catch (AccessControlException e) {
                // success
            }
        }
    }

    //---------------------------------------< getEffectivePolicies(String) >---
    @Test
    public void testGetEffectivePolicies() throws Exception {
        // TODO
    }

    @Test
    public void testGetEffectivePoliciesInvalidPath() throws Exception {
        for (String invalid : getInvalidPaths()) {
            try {
                acMgr.getEffectivePolicies(invalid);
                fail("Getting policies for an invalid path should fail");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testGetEffectivePoliciesForPropertyPath() throws Exception {
        String propertyPath = "/jcr:primaryType";
        try {
            acMgr.getEffectivePolicies(propertyPath);
            fail("Getting policies for property should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetEffectivePoliciesNonExistingNodePath() throws Exception {
        String nonExistingPath = "/not/existing";
        try {
            acMgr.getEffectivePolicies(nonExistingPath);
            fail("Getting policies for node that doesn't exist should fail.");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testGetEffectivePoliciesForAcContentPaths() throws Exception {
        for (String path : getAcContentPaths()) {
            try {
                acMgr.getEffectivePolicies(path);
                fail("Getting effective policies for access control content should fail.");
            } catch (AccessControlException e) {
                // success
            }
        }
    }

    //-----------------------------< setPolicy(String, AccessControlPolicy) >---
    @Test
    public void testSetPolicy() throws Exception {
        ACL acl = getApplicablePolicy(testPath);
        acl.addAccessControlEntry(testPrincipal, testPrivileges);
        acl.addEntry(EveryonePrincipal.getInstance(), testPrivileges, false, getGlobRestriction("*/something"));

        acMgr.setPolicy(testPath, acl);
        root.commit();

        Root root2 = adminSession.getLatestRoot();
        AccessControlPolicy[] policies = getAccessControlManager(root2).getPolicies(testPath);
        assertEquals(1, policies.length);
        assertArrayEquals(acl.getAccessControlEntries(), ((ACL) policies[0]).getAccessControlEntries());
    }

    @Test
    public void testSetPolicyWritesAcContent() throws Exception {
        ACL acl = getApplicablePolicy(testPath);
        acl.addAccessControlEntry(testPrincipal, testPrivileges);
        acl.addEntry(EveryonePrincipal.getInstance(), testPrivileges, false, getGlobRestriction("*/something"));

        acMgr.setPolicy(testPath, acl);
        root.commit();

        Root root2 = adminSession.getLatestRoot();
        Tree tree = root2.getTree(testPath);
        assertTrue(tree.hasChild(REP_POLICY));
        Tree policyTree = tree.getChild(REP_POLICY);
        assertEquals(NT_REP_ACL, TreeUtil.getPrimaryTypeName(policyTree));
        assertEquals(2, policyTree.getChildrenCount());

        Iterator<Tree> children = policyTree.getChildren().iterator();
        Tree ace = children.next();
        assertEquals(NT_REP_GRANT_ACE, TreeUtil.getPrimaryTypeName(ace));
        assertEquals(testPrincipal.getName(), TreeUtil.getString(ace, REP_PRINCIPAL_NAME));
        assertArrayEquals(testPrivileges, privilegesFromNames(TreeUtil.getStrings(ace, REP_PRIVILEGES)));
        assertFalse(ace.hasChild(REP_RESTRICTIONS));

        NodeUtil ace2 = new NodeUtil(children.next());
        assertEquals(NT_REP_DENY_ACE, ace2.getPrimaryNodeTypeName());
        assertEquals(EveryonePrincipal.NAME, ace2.getString(REP_PRINCIPAL_NAME, null));
        assertArrayEquals(testPrivileges, privilegesFromNames(ace2.getNames(REP_PRIVILEGES)));
        assertTrue(ace2.hasChild(REP_RESTRICTIONS));
        NodeUtil restr = ace2.getChild(REP_RESTRICTIONS);
        assertEquals("*/something", restr.getString(REP_GLOB, null));
    }

    @Test
    public void testModifyExistingPolicy() throws Exception {
        ACL acl = getApplicablePolicy(testPath);
        assertTrue(acl.addAccessControlEntry(testPrincipal, testPrivileges));
        AccessControlEntry allowTest = acl.getAccessControlEntries()[0];

        acMgr.setPolicy(testPath, acl);
        root.commit();

        acl = (ACL) acMgr.getPolicies(testPath)[0];
        assertTrue(acl.addEntry(EveryonePrincipal.getInstance(), testPrivileges, false, getGlobRestriction("*/something")));

        AccessControlEntry[] aces = acl.getAccessControlEntries();
        assertEquals(2, aces.length);
        AccessControlEntry denyEveryone = aces[1];
        assertEquals(EveryonePrincipal.getInstance(), denyEveryone.getPrincipal());

        acl.orderBefore(denyEveryone, allowTest);
        acMgr.setPolicy(testPath, acl);
        root.commit();

        acl = (ACL) acMgr.getPolicies(testPath)[0];
        aces = acl.getAccessControlEntries();
        assertEquals(2, aces.length);
        assertEquals(denyEveryone, aces[0]);
        assertEquals(allowTest, aces[1]);

        Privilege[] readAc = new Privilege[]{acMgr.privilegeFromName(PrivilegeConstants.JCR_READ_ACCESS_CONTROL)};
        assertTrue(acl.addEntry(testPrincipal, readAc, false, Collections.<String, Value>emptyMap()));
        assertEquals(3, acl.size());
        AccessControlEntry denyTest = acl.getAccessControlEntries()[2];

        acl.orderBefore(denyTest, allowTest);
        acMgr.setPolicy(testPath, acl);

        acl = (ACL) acMgr.getPolicies(testPath)[0];
        aces = acl.getAccessControlEntries();
        assertEquals(3, aces.length);

        assertEquals(denyEveryone, aces[0]);
        assertEquals(denyTest, aces[1]);
        assertEquals(allowTest, aces[2]);
    }

    @Test
    public void testSetInvalidPolicy() throws Exception {
        // TODO
    }

    @Test
    public void testSetRepoPolicy() throws Exception {
        // TODO
    }

    @Test
    public void testSetPolicyInvalidPath() throws Exception {
        for (String invalid : getInvalidPaths()) {
            try {
                AccessControlPolicy acl = createPolicy(invalid);
                acMgr.setPolicy(invalid, acl);
                fail("Setting access control policy with invalid path should fail");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testSetPolicyPropertyPath() throws Exception {
        try {
            String path = "/jcr:primaryType";
            AccessControlPolicy acl = createPolicy(path);
            acMgr.setPolicy(path, acl);
            fail("Setting access control policy at property path should fail");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testSetPolicyNonExistingNodePath() throws Exception {
        try {
            String path = "/non/existing";
            AccessControlPolicy acl = createPolicy(path);
            acMgr.setPolicy(path, acl);
            fail("Setting access control policy for non-existing node path should fail");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testSetPolicyAcContent() throws Exception {
        for (String acPath : getAcContentPaths()) {
            try {
                AccessControlPolicy acl = createPolicy(acPath);
                acMgr.setPolicy(acPath, acl);
                fail("Setting access control policy to access control content should fail");
            } catch (AccessControlException e) {
                // success
            }
        }
    }

    //--------------------------< removePolicy(String, AccessControlPolicy) >---
    @Test
    public void testRemovePolicy() throws Exception {
        // TODO
    }

    @Test
    public void testRemoveInvalidPolicy() throws Exception {
        // TODO
    }

    @Test
    public void testRemoveRepoPolicy() throws Exception {
        // TODO
    }

    @Test
    public void testRemovePolicyInvalidPath() throws Exception {
        for (String invalid : getInvalidPaths()) {
            try {
                AccessControlPolicy acl = createPolicy(invalid);
                acMgr.removePolicy(invalid, acl);
                fail("Removing access control policy with invalid path should fail");
            } catch (RepositoryException e) {
                // success
            }
        }
    }

    @Test
    public void testRemovePolicyPropertyPath() throws Exception {
        try {
            String path = "/jcr:primaryType";
            AccessControlPolicy acl = createPolicy(path);
            acMgr.removePolicy(path, acl);
            fail("Removing access control policy at property path should fail");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testRemovePolicyNonExistingNodePath() throws Exception {
        try {
            String path = "/non/existing";
            AccessControlPolicy acl = createPolicy(path);
            acMgr.removePolicy(path, acl);
            fail("Removing access control policy for non-existing node path should fail");
        } catch (PathNotFoundException e) {
            // success
        }
    }

    @Test
    public void testRemovePolicyAcContent() throws Exception {
        for (String acPath : getAcContentPaths()) {
            try {
                AccessControlPolicy acl = createPolicy(acPath);
                acMgr.removePolicy(acPath, acl);
                fail("Removing access control policy to access control content should fail");
            } catch (AccessControlException e) {
                // success
            }
        }
    }

    //-----------------------------------< getApplicablePolicies(Principal) >---
    // TODO

    //---------------------------------------------< getPolicies(Principal) >---
    // TODO

    //------------------------------------< getEffectivePolicies(Principal) >---
    // TODO
}
