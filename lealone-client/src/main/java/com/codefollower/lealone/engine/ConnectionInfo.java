/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import com.codefollower.lealone.constant.Constants;
import com.codefollower.lealone.constant.DbSettings;
import com.codefollower.lealone.constant.ErrorCode;
import com.codefollower.lealone.constant.SetTypes;
import com.codefollower.lealone.constant.SysProperties;
import com.codefollower.lealone.engine.ConnectionInfo;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.security.SHA256;
import com.codefollower.lealone.store.fs.FilePathRec;
import com.codefollower.lealone.store.fs.FileUtils;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.util.SortedProperties;
import com.codefollower.lealone.util.StringUtils;
import com.codefollower.lealone.util.Utils;
import com.codefollower.lealone.zookeeper.ZooKeeperAdmin;

/**
 * Encapsulates the connection settings, including user name and password.
 */
public class ConnectionInfo implements Cloneable {
    private static final HashSet<String> KNOWN_SETTINGS = New.hashSet();

    /** Default value for ZooKeeper session timeout */
    public static final int DEFAULT_ZK_SESSION_TIMEOUT = 180 * 1000;
    private ZooKeeperAdmin zkAdmin;

    private Properties prop = new Properties();
    private String originalURL;
    private String url;
    private String user;
    private byte[] filePasswordHash;
    private byte[] userPasswordHash;

    /**
     * The database name
     */
    private String name;
    private String nameNormalized;
    private boolean remote;
    private boolean ssl;
    private boolean persistent;
    private boolean unnamed;

    private String dbEngineName;
    private boolean embedded;
    private SessionFactory sessionFactory;
    private boolean dynamic;

    /**
     * Create a connection info object.
     *
     * @param name the database name (including tags), but without the
     *            "jdbc:lealone:" prefix
     */
    public ConnectionInfo(String name) {
        this.name = name;
        this.url = Constants.START_URL + name;
        parseName();
    }

    /**
     * Create a connection info object.
     *
     * @param u the database URL (must start with jdbc:lealone:)
     * @param info the connection properties
     */
    public ConnectionInfo(String u, Properties info) {
        u = remapURL(u);
        this.originalURL = u;
        if (!u.startsWith(Constants.START_URL)) {
            throw DbException.getInvalidValueException("url", u);
        }
        this.url = u;
        readProperties(info);
        readSettingsFromURL();
        setUserName(removeProperty("USER", ""));
        convertPasswords();
        name = url.substring(Constants.START_URL.length());
        parseName();
        String recoverTest = removeProperty("RECOVER_TEST", null);
        if (recoverTest != null) {
            FilePathRec.register();
            try {
                Utils.callStaticMethod("com.codefollower.lealone.store.RecoverTester.init", recoverTest);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
            name = "rec:" + name;
        }
    }

    static {
        ArrayList<String> list = SetTypes.getTypes();
        HashSet<String> set = KNOWN_SETTINGS;
        set.addAll(list);
        String[] connectionTime = { "ACCESS_MODE_DATA", "AUTOCOMMIT", "CIPHER", "CREATE", "CACHE_TYPE", "FILE_LOCK",
                "IGNORE_UNKNOWN_SETTINGS", "IFEXISTS", "INIT", "PASSWORD", "RECOVER", "RECOVER_TEST", "USER", "AUTO_SERVER",
                "AUTO_SERVER_PORT", "NO_UPGRADE", "AUTO_RECONNECT", "OPEN_NEW", "PAGE_SIZE", "PASSWORD_HASH", "JMX",
                "ZOOKEEPER_SESSION_TIMEOUT" };
        for (String key : connectionTime) {
            if (SysProperties.CHECK && set.contains(key)) {
                DbException.throwInternalError(key);
            }
            set.add(key);
        }
    }

    private static boolean isKnownSetting(String s) {
        return KNOWN_SETTINGS.contains(s);
    }

    public Object clone() throws CloneNotSupportedException {
        ConnectionInfo clone = (ConnectionInfo) super.clone();
        clone.prop = (Properties) prop.clone();
        clone.filePasswordHash = Utils.cloneByteArray(filePasswordHash);
        clone.userPasswordHash = Utils.cloneByteArray(userPasswordHash);
        return clone;
    }

    private void parseName() {
        if (".".equals(name)) {
            name = "mem:";
        }
        if (name.startsWith("tcp:")) {
            remote = true;
            name = name.substring("tcp:".length());
        } else if (name.startsWith("ssl:")) {
            remote = true;
            ssl = true;
            name = name.substring("ssl:".length());
        } else if (name.startsWith("mem:")) { //不推荐再使用，用embedded:memory替换
            persistent = false;
            if ("mem:".equals(name)) {
                unnamed = true;
            }
            embedded = true;
            dbEngineName = "MEMORY";
        } else if (name.startsWith("file:")) { //不推荐再使用，用embedded:regular替换
            name = name.substring("file:".length());
            persistent = true;
            embedded = true;
            dbEngineName = "REGULAR";
        } else if (name.startsWith("embedded:")) {
            embedded = true;
            name = name.substring("embedded:".length());
            int pos = name.indexOf(':');
            if (pos == -1)
                throw getFormatException();
            else {
                dbEngineName = name.substring(0, pos);
                name = name.substring(pos + 1);
                if ("".equals(name)) {
                    unnamed = true;
                }
            }
        } else if (name.startsWith("dynamic:")) {
            dynamic = true;
            name = name.substring("dynamic:".length());
        } else { //不推荐再使用，用embedded:regular替换
            persistent = true;
            embedded = true;
            dbEngineName = "REGULAR";
        }

        if ("default".equalsIgnoreCase(dbEngineName))
            dbEngineName = getDbSettings().defaultDatabaseEngine;
        if ("MEMORY".equalsIgnoreCase(dbEngineName))
            persistent = false;
        else if ("REGULAR".equalsIgnoreCase(dbEngineName) || "MVSTORE".equalsIgnoreCase(dbEngineName))
            persistent = true;
        if (persistent && !remote) {
            if ("/".equals(SysProperties.FILE_SEPARATOR)) {
                name = name.replace('\\', '/');
            } else {
                name = name.replace('/', '\\');
            }
        }
    }

    /**
     * Set the base directory of persistent databases, unless the database is in
     * the user home folder (~).
     *
     * @param dir the new base directory
     */
    public void setBaseDir(String dir) {
        if (persistent) {
            String absDir = FileUtils.unwrap(FileUtils.toRealPath(dir));
            boolean absolute = FileUtils.isAbsolute(name);
            String n;
            String prefix = null;
            if (dir.endsWith(SysProperties.FILE_SEPARATOR)) {
                dir = dir.substring(0, dir.length() - 1);
            }
            if (absolute) {
                n = name;
            } else {
                n = FileUtils.unwrap(name);
                prefix = name.substring(0, name.length() - n.length());
                n = dir + SysProperties.FILE_SEPARATOR + n;
            }
            String normalizedName = FileUtils.unwrap(FileUtils.toRealPath(n));
            if (normalizedName.equals(absDir) || !normalizedName.startsWith(absDir)) {
                throw DbException.get(ErrorCode.IO_EXCEPTION_1, normalizedName + " outside " + absDir);
            }
            if (!absolute) {
                name = prefix + dir + SysProperties.FILE_SEPARATOR + FileUtils.unwrap(name);
            }
        }
    }

    /**
     * Check if this is a remote connection.
     *
     * @return true if it is
     */
    public boolean isRemote() {
        return remote;
    }

    /**
     * Check if the referenced database is persistent.
     *
     * @return true if it is
     */
    //    public boolean isPersistent() {
    //        return persistent;
    //    }

    /**
     * Check if the referenced database is an unnamed in-memory database.
     *
     * @return true if it is
     */
    boolean isUnnamedInMemory() {
        return unnamed;
    }

    public void readProperties(Properties info) {
        Object[] list = new Object[info.size()];
        info.keySet().toArray(list);
        DbSettings s = null;
        for (Object k : list) {
            String key = StringUtils.toUpperEnglish(k.toString());
            if (prop.containsKey(key)) {
                throw DbException.get(ErrorCode.DUPLICATE_PROPERTY_1, key);
            }
            Object value = info.get(k);
            if (isKnownSetting(key)) {
                prop.put(key, value);
            } else {
                if (s == null) {
                    s = getDbSettings();
                }
                if (s.containsKey(key)) {
                    prop.put(key, value);
                }
            }
        }
    }

    private void readSettingsFromURL() {
        DbSettings dbSettings = DbSettings.getInstance(null);
        int idx = url.indexOf(';');
        if (idx >= 0) {
            String settings = url.substring(idx + 1);
            url = url.substring(0, idx);
            String[] list = StringUtils.arraySplit(settings, ';', false);
            for (String setting : list) {
                if (setting.length() == 0) {
                    continue;
                }
                int equal = setting.indexOf('=');
                if (equal < 0) {
                    throw getFormatException();
                }
                String value = setting.substring(equal + 1);
                String key = setting.substring(0, equal);
                key = StringUtils.toUpperEnglish(key);
                if (!isKnownSetting(key) && !dbSettings.containsKey(key)) {
                    throw DbException.get(ErrorCode.UNSUPPORTED_SETTING_1, key);
                }
                String old = prop.getProperty(key);
                if (old != null && !old.equals(value)) {
                    throw DbException.get(ErrorCode.DUPLICATE_PROPERTY_1, key);
                }
                prop.setProperty(key, value);
            }
        }
    }

    private char[] removePassword() {
        Object p = prop.remove("PASSWORD");
        if (p == null) {
            return new char[0];
        } else if (p instanceof char[]) {
            return (char[]) p;
        } else {
            return p.toString().toCharArray();
        }
    }

    /**
     * Split the password property into file password and user password if
     * necessary, and convert them to the internal hash format.
     */
    private void convertPasswords() {
        char[] password = removePassword();
        boolean passwordHash = removeProperty("PASSWORD_HASH", false);
        if (getProperty("CIPHER", null) != null) {
            // split password into (filePassword+' '+userPassword)
            int space = -1;
            for (int i = 0, len = password.length; i < len; i++) {
                if (password[i] == ' ') {
                    space = i;
                    break;
                }
            }
            if (space < 0) {
                throw DbException.get(ErrorCode.WRONG_PASSWORD_FORMAT);
            }
            char[] np = new char[password.length - space - 1];
            char[] filePassword = new char[space];
            System.arraycopy(password, space + 1, np, 0, np.length);
            System.arraycopy(password, 0, filePassword, 0, space);
            Arrays.fill(password, (char) 0);
            password = np;
            filePasswordHash = hashPassword(passwordHash, "file", filePassword);
        }
        userPasswordHash = hashPassword(passwordHash, user, password);
    }

    private static byte[] hashPassword(boolean passwordHash, String userName, char[] password) {
        if (passwordHash) {
            return StringUtils.convertHexToBytes(new String(password));
        }
        if (userName.length() == 0 && password.length == 0) {
            return new byte[0];
        }
        return SHA256.getKeyPasswordHash(userName, password);
    }

    /**
     * Get a boolean property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    boolean getProperty(String key, boolean defaultValue) {
        String x = getProperty(key, null);
        if (x == null) {
            return defaultValue;
        }
        // support 0 / 1 (like the parser)
        if (x.length() == 1 && Character.isDigit(x.charAt(0))) {
            return Integer.parseInt(x) != 0;
        }
        return Boolean.parseBoolean(x);
    }

    /**
     * Remove a boolean property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    public boolean removeProperty(String key, boolean defaultValue) {
        String x = removeProperty(key, null);
        return x == null ? defaultValue : Boolean.parseBoolean(x);
    }

    /**
     * Remove a String property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    String removeProperty(String key, String defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            DbException.throwInternalError(key);
        }
        Object x = prop.remove(key);
        return x == null ? defaultValue : x.toString();
    }

    /**
     * Get the unique and normalized database name (excluding settings).
     *
     * @return the database name
     */
    public String getName() {
        if (persistent) {
            if (nameNormalized == null) {
                String suffix = Constants.SUFFIX_PAGE_FILE;
                String n = FileUtils.toRealPath(name + suffix);
                String fileName = FileUtils.getName(n);
                if (fileName.length() < suffix.length() + 1) {
                    throw DbException.get(ErrorCode.INVALID_DATABASE_NAME_1, name);
                }
                nameNormalized = n.substring(0, n.length() - suffix.length());
            }
            return nameNormalized;
        }
        return name;
    }

    /**
     * Get the file password hash if it is set.
     *
     * @return the password hash or null
     */
    byte[] getFilePasswordHash() {
        return filePasswordHash;
    }

    /**
     * Get the name of the user.
     *
     * @return the user name
     */
    public String getUserName() {
        return user;
    }

    /**
     * Get the user password hash.
     *
     * @return the password hash
     */
    byte[] getUserPasswordHash() {
        return userPasswordHash;
    }

    /**
     * Get the property keys.
     *
     * @return the property keys
     */
    String[] getKeys() {
        String[] keys = new String[prop.size()];
        prop.keySet().toArray(keys);
        return keys;
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @return the value as a String
     */
    public String getProperty(String key) {
        Object value = prop.get(key);
        if (value == null || !(value instanceof String)) {
            return null;
        }
        return value.toString();
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the value as a String
     */
    int getProperty(String key, int defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            DbException.throwInternalError(key);
        }
        String s = getProperty(key);
        return s == null ? defaultValue : Integer.parseInt(s);
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the value as a String
     */
    public String getProperty(String key, String defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            DbException.throwInternalError(key);
        }
        String s = getProperty(key);
        return s == null ? defaultValue : s;
    }

    /**
     * Get the value of the given property.
     *
     * @param setting the setting id
     * @param defaultValue the default value
     * @return the value as a String
     */
    String getProperty(int setting, String defaultValue) {
        String key = SetTypes.getTypeName(setting);
        String s = getProperty(key);
        return s == null ? defaultValue : s;
    }

    /**
     * Get the value of the given property.
     *
     * @param setting the setting id
     * @param defaultValue the default value
     * @return the value as an integer
     */
    int getIntProperty(int setting, int defaultValue) {
        String key = SetTypes.getTypeName(setting);
        String s = getProperty(key, null);
        try {
            return s == null ? defaultValue : Integer.decode(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if this is a remote connection with SSL enabled.
     *
     * @return true if it is
     */
    boolean isSSL() {
        return ssl;
    }

    /**
     * Overwrite the user name. The user name is case-insensitive and stored in
     * uppercase. English conversion is used.
     *
     * @param name the user name
     */
    public void setUserName(String name) {
        this.user = StringUtils.toUpperEnglish(name);
    }

    /**
     * Set the user password hash.
     *
     * @param hash the new hash value
     */
    public void setUserPasswordHash(byte[] hash) {
        this.userPasswordHash = hash;
    }

    /**
     * Set the file password hash.
     *
     * @param hash the new hash value
     */
    public void setFilePasswordHash(byte[] hash) {
        this.filePasswordHash = hash;
    }

    /**
     * Overwrite a property.
     *
     * @param key the property name
     * @param value the value
     */
    public void setProperty(String key, String value) {
        // value is null if the value is an object
        if (value != null) {
            prop.setProperty(key, value);
        }
    }

    /**
     * Get the database URL.
     *
     * @return the URL
     */
    public String getURL() {
        return url;
    }

    /**
     * Get the complete original database URL.
     *
     * @return the database URL
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Set the original database URL.
     *
     * @param url the database url
     */
    public void setOriginalURL(String url) {
        originalURL = url;
    }

    /**
     * Generate an URL format exception.
     *
     * @return the exception
     */
    DbException getFormatException() {
        String format = Constants.URL_FORMAT;
        return DbException.get(ErrorCode.URL_FORMAT_ERROR_2, format, url);
    }

    /**
     * Switch to server mode, and set the server name and database key.
     *
     * @param serverKey the server name, '/', and the security key
     */
    public void setServerKey(String serverKey) {
        remote = true;
        persistent = false;
        this.name = serverKey;
    }

    DbSettings getDbSettings() {
        DbSettings defaultSettings = DbSettings.getInstance(null);
        HashMap<String, String> s = null;
        for (Object k : prop.keySet()) {
            String key = k.toString();
            if (!isKnownSetting(key) && defaultSettings.containsKey(key)) {
                if (s == null) {
                    s = New.hashMap();
                }
                s.put(key, prop.getProperty(key));
            }
        }
        return DbSettings.getInstance(s);
    }

    private static String remapURL(String url) {
        String urlMap = SysProperties.URL_MAP;
        if (urlMap != null && urlMap.length() > 0) {
            try {
                SortedProperties prop;
                prop = SortedProperties.loadProperties(urlMap);
                String url2 = prop.getProperty(url);
                if (url2 == null) {
                    prop.put(url, "");
                    prop.store(urlMap);
                } else {
                    url2 = url2.trim();
                    if (url2.length() > 0) {
                        return url2;
                    }
                }
            } catch (IOException e) {
                throw DbException.convert(e);
            }
        }
        return url;
    }

    public SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            try {
                String name;
                if (embedded) {
                    name = dbEngineName;
                } else if (!persistent)
                    name = "MEMORY";
                else
                    name = getDbSettings().defaultDatabaseEngine;
                sessionFactory = (SessionFactory) Utils.callStaticMethod(
                        "com.codefollower.lealone.engine.DatabaseEngineManager.getDatabaseEngine", name);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
        return sessionFactory;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public String getOnlineServer(String quorum) {
        if (zkAdmin == null) {
            int sessionTimeout = getProperty("ZOOKEEPER_SESSION_TIMEOUT", DEFAULT_ZK_SESSION_TIMEOUT);
            removeProperty("ZOOKEEPER_SESSION_TIMEOUT", null);
            zkAdmin = new ZooKeeperAdmin(quorum, sessionTimeout);
        }
        return zkAdmin.getOnlineServer();
    }

}
