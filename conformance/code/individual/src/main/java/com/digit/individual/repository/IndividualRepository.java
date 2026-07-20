package com.digit.individual.repository;

import com.digit.individual.model.Address;
import com.digit.individual.model.Document;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import com.digit.individual.model.SearchCriteria;
import com.digit.individual.constants.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.observability.ObservabilityMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC repository for individuals and their nested entities. Mirrors Go
 * internal/repository/individual_repository.go (GORM) using plain SQL against the v3 tables.
 */
@Repository
public class IndividualRepository {

    private static final String T = "individual_v3";
    private static final String T_ADDR = "individual_address_v3";
    private static final String T_IDENT = "individual_identifier_v3";
    private static final String T_DOC = "individual_document_v3";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final ObservabilityMetrics metrics;

    public IndividualRepository(JdbcTemplate jdbc, JsonMapper mapper, ObservabilityMetrics metrics) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // ----------------------------------------------------------------- mappers

    private final RowMapper<Individual> individualMapper = (RowMapper<Individual>) (ResultSet rs, int n) -> {
        Individual e = new Individual();
        e.setId(rs.getString("id"));
        e.setIndividualId(rs.getString("individualid"));
        e.setTenantId(rs.getString("tenantid"));
        e.setGivenName(rs.getString("givenname"));
        e.setFamilyName(rs.getString("familyname"));
        e.setOtherNames(rs.getString("othernames"));
        Date dob = rs.getDate("dateofbirth");
        e.setDateOfBirth(dob == null ? null : dob.toLocalDate());
        e.setGender(rs.getString("gender"));
        int age = rs.getInt("age");
        e.setAge(rs.wasNull() ? null : age);
        e.setMobileNumber(rs.getString("mobilenumber"));
        e.setHashedMobileNumber(rs.getString("hashedmobilenumber"));
        e.setMobileNumberVerified(rs.getBoolean("mobilenumberverified"));
        e.setAltContactNumber(rs.getString("altcontactnumber"));
        e.setEmail(rs.getString("email"));
        e.setEmailVerified(rs.getBoolean("emailverified"));
        e.setLocale(rs.getString("locale"));
        e.setActive(rs.getBoolean("active"));
        e.setFatherName(rs.getString("fathername"));
        e.setHusbandName(rs.getString("husbandname"));
        e.setPhoto(rs.getString("photo"));
        e.setUserId(rs.getString("userid"));
        e.setAdditionalDetails(parseJsonObject(rs.getString("additionaldetails")));
        e.setCreatedBy(rs.getString("createdBy"));
        e.setModifiedBy(rs.getString("modifiedBy"));
        e.setCreatedTime(rs.getLong("createdTime"));
        e.setModifiedTime(rs.getLong("modifiedTime"));
        e.setRowVersion(rs.getInt("rowversion"));
        e.setRequestId(rs.getString("requestid"));
        return e;
    };

    private static final String IND_COLS =
            "id, individualid, tenantid, givenname, familyname, othernames, dateofbirth, gender, age, "
                    + "mobilenumber, hashedmobilenumber, mobilenumberverified, altcontactnumber, email, "
                    + "emailverified, locale, active, fathername, husbandname, photo, userid, additionaldetails, "
                    + "\"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\", rowversion, requestid";

    private final RowMapper<Address> addressMapper = (RowMapper<Address>) (ResultSet rs, int n) -> {
        Address a = new Address();
        a.setId(rs.getString("id"));
        a.setTenantId(rs.getString("tenantid"));
        a.setType(rs.getString("type"));
        a.setDoorNo(rs.getString("doorno"));
        a.setBuildingName(rs.getString("buildingname"));
        a.setStreet(rs.getString("street"));
        a.setLandmark(rs.getString("landmark"));
        a.setAddressLine1(rs.getString("addressline1"));
        a.setAddressLine2(rs.getString("addressline2"));
        a.setCity(rs.getString("city"));
        a.setRegion(rs.getString("region"));
        a.setCountry(rs.getString("country"));
        a.setPincode(rs.getString("pincode"));
        a.setBoundaryCode(rs.getString("localitycode"));
        a.setLatitude(getNullableDouble(rs, "latitude"));
        a.setLongitude(getNullableDouble(rs, "longitude"));
        a.setLocationAccuracy(getNullableDouble(rs, "locationaccuracy"));
        a.setCreatedBy(rs.getString("createdBy"));
        a.setModifiedBy(rs.getString("modifiedBy"));
        a.setCreatedTime(rs.getLong("createdTime"));
        a.setModifiedTime(rs.getLong("modifiedTime"));
        a.setRequestId(rs.getString("requestid"));
        return a;
    };

    private final RowMapper<Identifier> identifierMapper = (RowMapper<Identifier>) (ResultSet rs, int n) -> {
        Identifier i = new Identifier();
        i.setId(rs.getString("id"));
        i.setIndividualId(rs.getString("individualid"));
        i.setIdentifierType(rs.getString("identifiertype"));
        i.setIdentifierId(rs.getString("identifierid"));
        i.setVerified(rs.getBoolean("verified"));
        i.setDocumentType(rs.getString("documenttype"));
        i.setFileStoreId(rs.getString("filestoreid"));
        i.setActive(rs.getBoolean("active"));
        i.setCreatedBy(rs.getString("createdBy"));
        i.setModifiedBy(rs.getString("modifiedBy"));
        i.setCreatedTime(rs.getLong("createdTime"));
        i.setModifiedTime(rs.getLong("modifiedTime"));
        i.setRequestId(rs.getString("requestid"));
        return i;
    };

    private final RowMapper<Document> documentMapper = (RowMapper<Document>) (ResultSet rs, int n) -> {
        Document d = new Document();
        d.setId(rs.getString("id"));
        d.setIndividualId(rs.getString("individualid"));
        d.setDocumentType(rs.getString("documenttype"));
        d.setFileStoreId(rs.getString("filestoreid"));
        d.setDocumentUid(rs.getString("documentuid"));
        d.setCreatedBy(rs.getString("createdBy"));
        d.setModifiedBy(rs.getString("modifiedBy"));
        d.setCreatedTime(rs.getLong("createdTime"));
        d.setModifiedTime(rs.getLong("modifiedTime"));
        d.setRequestId(rs.getString("requestid"));
        return d;
    };

    private static Double getNullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize additionalDetails JSON", e);
        }
    }

    private static Date toSqlDate(java.time.LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    // ----------------------------------------------------------------- writes

    /**
     * Inserts an individual plus its nested address/identifier/document rows. Mirrors Go Create,
     * whose GORM {@code db.Transaction(...)} wraps all statements so the parent and every child row
     * commit or roll back together. {@code PROPAGATION_REQUIRED} joins the request-scoped transaction
     * opened by {@code TenantTransactionFilter} for HTTP calls, and opens its own for any non-HTTP
     * caller that bypasses the servlet filter.
     */
    @Transactional
    public void create(Individual ind) {
        boolean ok = true;
        try {
            jdbc.update("INSERT INTO " + T + " (id, individualid, tenantid, givenname, familyname, othernames, "
                            + "dateofbirth, gender, age, mobilenumber, hashedmobilenumber, mobilenumberverified, "
                            + "altcontactnumber, email, emailverified, locale, active, fathername, husbandname, "
                            + "photo, userid, additionaldetails, \"createdBy\", \"modifiedBy\", \"createdTime\", "
                            + "\"modifiedTime\", rowversion, requestid) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?)",
                    ind.getId(), ind.getIndividualId(), ind.getTenantId(), ind.getGivenName(), ind.getFamilyName(),
                    ind.getOtherNames(), toSqlDate(ind.getDateOfBirth()), ind.getGender(), ind.getAge(),
                    ind.getMobileNumber(), ind.getHashedMobileNumber(), ind.isMobileNumberVerified(),
                    ind.getAltContactNumber(), ind.getEmail(), ind.isEmailVerified(), ind.getLocale(),
                    ind.isActive(), ind.getFatherName(), ind.getHusbandName(), ind.getPhoto(), ind.getUserId(),
                    writeJson(ind.getAdditionalDetails()), ind.getCreatedBy(), ind.getModifiedBy(),
                    ind.getCreatedTime(), ind.getModifiedTime(), ind.getRowVersion(), ind.getRequestId());

            for (Address a : ind.getAddresses()) {
                a.setIndividualId(ind.getId());
                insertAddress(a);
            }
            for (Identifier i : ind.getIdentifiers()) {
                i.setIndividualId(ind.getId());
                insertIdentifier(i);
            }
            for (Document d : ind.getDocuments()) {
                d.setIndividualId(ind.getId());
                insertDocument(d);
            }
        } catch (DuplicateKeyException e) {
            // Postgres 23505 unique-constraint violation → 409 (race backstop behind the app-level
            // uniqueness check). Translated here in the repo so the tracer doesn't report a 500.
            ok = false;
            throw duplicateConflict();
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", T, ok);
        }
    }

    private void insertAddress(Address a) {
        jdbc.update("INSERT INTO " + T_ADDR + " (id, individualid, tenantid, type, doorno, buildingname, street, landmark, "
                        + "addressline1, addressline2, city, region, country, pincode, localitycode, latitude, "
                        + "longitude, locationaccuracy, \"createdBy\", \"modifiedBy\", \"createdTime\", "
                        + "\"modifiedTime\", requestid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                a.getId(), a.getIndividualId(), a.getTenantId(), a.getType(), a.getDoorNo(), a.getBuildingName(), a.getStreet(),
                a.getLandmark(), a.getAddressLine1(), a.getAddressLine2(), a.getCity(), a.getRegion(),
                a.getCountry(), a.getPincode(), a.getBoundaryCode(), a.getLatitude(), a.getLongitude(),
                a.getLocationAccuracy(), a.getCreatedBy(), a.getModifiedBy(), a.getCreatedTime(),
                a.getModifiedTime(), a.getRequestId());
    }

    private void insertIdentifier(Identifier i) {
        jdbc.update("INSERT INTO " + T_IDENT + " (id, individualid, identifiertype, identifierid, verified, "
                        + "documenttype, filestoreid, active, \"createdBy\", \"modifiedBy\", \"createdTime\", "
                        + "\"modifiedTime\", requestid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                i.getId(), i.getIndividualId(), i.getIdentifierType(), i.getIdentifierId(), i.isVerified(),
                i.getDocumentType(), i.getFileStoreId(), i.isActive(), i.getCreatedBy(), i.getModifiedBy(),
                i.getCreatedTime(), i.getModifiedTime(), i.getRequestId());
    }

    private void insertDocument(Document d) {
        jdbc.update("INSERT INTO " + T_DOC + " (id, individualid, documenttype, filestoreid, documentuid, "
                        + "\"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\", requestid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                d.getId(), d.getIndividualId(), d.getDocumentType(), d.getFileStoreId(), d.getDocumentUid(),
                d.getCreatedBy(), d.getModifiedBy(), d.getCreatedTime(), d.getModifiedTime(), d.getRequestId());
    }

    /**
     * Full-row update of the individual plus upsert of nested rows, in one transaction. The main-row
     * write is an optimistic compare-and-swap guarded by expectedVersion; returns false (nothing
     * written) when the version no longer matches, so the caller can surface a 409.
     */
    @Transactional
    public boolean update(Individual ind, int expectedVersion) {
        boolean ok = true;
        try {
            // Optimistic compare-and-swap: only write if the row's version still matches what the
            // client read. Zero rows affected => it changed in the meantime => version conflict
            // (caller returns 409); children are skipped.
            int rows = jdbc.update("UPDATE " + T + " SET individualid=?, tenantid=?, givenname=?, familyname=?, othernames=?, "
                            + "dateofbirth=?, gender=?, age=?, mobilenumber=?, hashedmobilenumber=?, "
                            + "mobilenumberverified=?, altcontactnumber=?, email=?, emailverified=?, locale=?, "
                            + "active=?, fathername=?, husbandname=?, photo=?, userid=?, additionaldetails=?::jsonb, "
                            + "\"createdBy\"=?, \"modifiedBy\"=?, \"createdTime\"=?, \"modifiedTime\"=?, "
                            + "rowversion=?, requestid=? WHERE id=? AND tenantid=? AND active=? AND rowversion=?",
                    ind.getIndividualId(), ind.getTenantId(), ind.getGivenName(), ind.getFamilyName(),
                    ind.getOtherNames(), toSqlDate(ind.getDateOfBirth()), ind.getGender(), ind.getAge(),
                    ind.getMobileNumber(), ind.getHashedMobileNumber(), ind.isMobileNumberVerified(),
                    ind.getAltContactNumber(), ind.getEmail(), ind.isEmailVerified(), ind.getLocale(),
                    ind.isActive(), ind.getFatherName(), ind.getHusbandName(), ind.getPhoto(), ind.getUserId(),
                    writeJson(ind.getAdditionalDetails()), ind.getCreatedBy(), ind.getModifiedBy(),
                    ind.getCreatedTime(), ind.getModifiedTime(), ind.getRowVersion(), ind.getRequestId(),
                    ind.getId(), ind.getTenantId(), true, expectedVersion);
            if (rows == 0) {
                return false;
            }

            for (Address a : ind.getAddresses()) {
                a.setIndividualId(ind.getId());
                if (updateAddress(a) == 0) {
                    insertAddress(a);
                }
            }
            for (Identifier i : ind.getIdentifiers()) {
                i.setIndividualId(ind.getId());
                // Scoped update (id + individualid): matches only a row this individual owns, so a
                // foreign id can't be reassigned. Zero rows => new/unmatched id => insert.
                if (updateIdentifier(i) == 0) {
                    insertIdentifier(i);
                }
            }
            for (Document d : ind.getDocuments()) {
                d.setIndividualId(ind.getId());
                if (updateDocument(d) == 0) {
                    insertDocument(d);
                }
            }

            // PUT full-replace: deactivate any existing active child of this individual not present in
            // the request. Enrichment set active=true on every request child, so the keep set is all
            // request child ids; an empty keep set means deactivate all of that type.
            List<String> keepDocs = new ArrayList<>();
            for (Document d : ind.getDocuments()) { if (d.getId() != null && !d.getId().isEmpty()) keepDocs.add(d.getId()); }
            deactivateOmittedByIndividual(T_DOC, ind.getId(), keepDocs);

            List<String> keepIdents = new ArrayList<>();
            for (Identifier i : ind.getIdentifiers()) { if (i.getId() != null && !i.getId().isEmpty()) keepIdents.add(i.getId()); }
            deactivateOmittedByIndividual(T_IDENT, ind.getId(), keepIdents);

            List<String> keepAddrs = new ArrayList<>();
            for (Address a : ind.getAddresses()) { if (a.getId() != null && !a.getId().isEmpty()) keepAddrs.add(a.getId()); }
            deactivateOmittedByIndividual(T_ADDR, ind.getId(), keepAddrs);

            return true;
        } catch (DuplicateKeyException e) {
            ok = false;
            throw duplicateConflict();
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", T, ok);
        }
    }

    /** 409 for a DB unique-constraint violation (Postgres 23505) — the race backstop behind the
     *  app-level uniqueness check. Rendered by the tracer's CustomException handler. */
    private static CustomException duplicateConflict() {
        return new CustomException(ErrorCodes.DUPLICATE, "Duplicate value violates a unique constraint",
                HttpStatus.CONFLICT);
    }

    private int updateAddress(Address a) {
        return jdbc.update("UPDATE " + T_ADDR + " SET tenantid=?, type=?, doorno=?, buildingname=?, street=?, landmark=?, "
                        + "addressline1=?, addressline2=?, city=?, region=?, country=?, pincode=?, localitycode=?, "
                        + "latitude=?, longitude=?, locationaccuracy=?, active=?, \"createdBy\"=?, \"modifiedBy\"=?, "
                        + "\"createdTime\"=?, \"modifiedTime\"=?, requestid=? WHERE id=? AND individualid=?",
                a.getTenantId(), a.getType(), a.getDoorNo(), a.getBuildingName(), a.getStreet(), a.getLandmark(),
                a.getAddressLine1(), a.getAddressLine2(), a.getCity(), a.getRegion(), a.getCountry(),
                a.getPincode(), a.getBoundaryCode(), a.getLatitude(), a.getLongitude(), a.getLocationAccuracy(),
                a.isActive(), a.getCreatedBy(), a.getModifiedBy(), a.getCreatedTime(), a.getModifiedTime(), a.getRequestId(),
                a.getId(), a.getIndividualId());
    }

    private int updateIdentifier(Identifier i) {
        return jdbc.update("UPDATE " + T_IDENT + " SET individualid=?, identifiertype=?, identifierid=?, verified=?, "
                        + "documenttype=?, filestoreid=?, active=?, \"createdBy\"=?, \"modifiedBy\"=?, "
                        + "\"createdTime\"=?, \"modifiedTime\"=?, requestid=? WHERE id=? AND individualid=?",
                i.getIndividualId(), i.getIdentifierType(), i.getIdentifierId(), i.isVerified(),
                i.getDocumentType(), i.getFileStoreId(), i.isActive(), i.getCreatedBy(), i.getModifiedBy(),
                i.getCreatedTime(), i.getModifiedTime(), i.getRequestId(), i.getId(), i.getIndividualId());
    }

    private int updateDocument(Document d) {
        return jdbc.update("UPDATE " + T_DOC + " SET individualid=?, documenttype=?, filestoreid=?, documentuid=?, active=?, "
                        + "\"createdBy\"=?, \"modifiedBy\"=?, \"createdTime\"=?, \"modifiedTime\"=?, requestid=? "
                        + "WHERE id=? AND individualid=?",
                d.getIndividualId(), d.getDocumentType(), d.getFileStoreId(), d.getDocumentUid(), d.isActive(),
                d.getCreatedBy(), d.getModifiedBy(), d.getCreatedTime(), d.getModifiedTime(), d.getRequestId(),
                d.getId(), d.getIndividualId());
    }

    /** Deactivates active child rows for the individual whose id is not in keepIds (empty keepIds => all). */
    private void deactivateOmittedByIndividual(String table, String individualId, List<String> keepIds) {
        StringBuilder sql = new StringBuilder("UPDATE " + table + " SET active=? WHERE individualid=? AND active=?");
        List<Object> args = new ArrayList<>();
        args.add(false);
        args.add(individualId);
        args.add(true);
        if (!keepIds.isEmpty()) {
            sql.append(" AND id NOT IN (").append(placeholders(keepIds.size())).append(")");
            args.addAll(keepIds);
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * Soft delete: deactivate the individual and all its children (identifiers, documents, addresses)
     * atomically in one transaction — no half-deleted record if a child update fails.
     */
    @Transactional
    public void delete(String id, String tenantId, long now) {
        boolean ok = true;
        try {
            jdbc.update("UPDATE " + T + " SET active=?, \"modifiedTime\"=? WHERE id=? AND tenantid=?",
                    false, now, id, tenantId);
            jdbc.update("UPDATE " + T_IDENT + " SET active=? WHERE individualid=?", false, id);
            jdbc.update("UPDATE " + T_DOC + " SET active=? WHERE individualid=?", false, id);
            jdbc.update("UPDATE " + T_ADDR + " SET active=? WHERE individualid=?", false, id);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", T, ok);
        }
    }

    // ----------------------------------------------------------------- reads

    /** Loads an active individual by id+tenant, with addresses/identifiers/documents. Mirrors Go FindByID. */
    public Individual findById(String id, String tenantId) {
        try {
            List<Individual> rows = jdbc.query(
                    "SELECT " + IND_COLS + " FROM " + T + " WHERE id=? AND tenantid=? AND active=? LIMIT 1",
                    individualMapper, id, tenantId, true);
            if (rows.isEmpty()) {
                metrics.recordDbOperation("SELECT", T, true);
                return null;
            }
            Individual ind = rows.get(0);
            loadChildren(ind);
            metrics.recordDbOperation("SELECT", T, true);
            return ind;
        } catch (RuntimeException e) {
            metrics.recordDbOperation("SELECT", T, false);
            throw e;
        }
    }

    private void loadChildren(Individual ind) {
        ind.setAddresses(jdbc.query(
                "SELECT id, tenantid, type, doorno, buildingname, street, landmark, addressline1, "
                        + "addressline2, city, region, country, pincode, localitycode, latitude, "
                        + "longitude, locationaccuracy, \"createdBy\", \"modifiedBy\", \"createdTime\", "
                        + "\"modifiedTime\", requestid FROM " + T_ADDR
                        + " WHERE individualid = ? AND active = ?",
                addressMapper, ind.getId(), true));
        ind.setIdentifiers(jdbc.query(
                "SELECT id, individualid, identifiertype, identifierid, verified, documenttype, filestoreid, "
                        + "active, \"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\", requestid "
                        + "FROM " + T_IDENT + " WHERE individualid = ? AND active = ?",
                identifierMapper, ind.getId(), true));
        ind.setDocuments(jdbc.query(
                "SELECT id, individualid, documenttype, filestoreid, documentuid, \"createdBy\", \"modifiedBy\", "
                        + "\"createdTime\", \"modifiedTime\", requestid FROM " + T_DOC + " WHERE individualid = ? AND active = ?",
                documentMapper, ind.getId(), true));
    }

    /** Builds the shared WHERE clause + args for Search/Exists. Mirrors Go buildSearchQuery. */
    private void buildWhere(SearchCriteria c, String tenantId, boolean includeDeleted,
                            StringBuilder sql, List<Object> args) {
        sql.append(" WHERE tenantid = ?");
        args.add(tenantId);
        if (!includeDeleted) {
            sql.append(" AND active = ?");
            args.add(true);
        }
        if (c == null) {
            return;
        }
        if (c.getId() != null && !c.getId().isEmpty()) {
            sql.append(" AND id IN (").append(placeholders(c.getId().size())).append(")");
            args.addAll(c.getId());
        }
        if (c.getIndividualId() != null && !c.getIndividualId().isEmpty()) {
            sql.append(" AND individualid IN (").append(placeholders(c.getIndividualId().size())).append(")");
            args.addAll(c.getIndividualId());
        }
        if (c.getGivenName() != null && !c.getGivenName().isEmpty()) {
            sql.append(" AND givenname ILIKE ?");
            args.add("%" + c.getGivenName() + "%");
        }
        if (c.getMobileNumber() != null && !c.getMobileNumber().isEmpty()) {
            sql.append(" AND hashedmobilenumber IN (").append(placeholders(c.getMobileNumber().size())).append(")");
            args.addAll(c.getMobileNumber());
        }
        if (c.getGender() != null && !c.getGender().isEmpty()) {
            sql.append(" AND gender = ?");
            args.add(c.getGender());
        }
        if (c.getDateOfBirth() != null && !c.getDateOfBirth().isEmpty()) {
            sql.append(" AND dateofbirth = ?");
            args.add(c.getDateOfBirth());
        }
        if (c.getUserId() != null && !c.getUserId().isEmpty()) {
            sql.append(" AND userid IN (").append(placeholders(c.getUserId().size())).append(")");
            args.addAll(c.getUserId());
        }
        if (c.getCreatedFrom() != null) {
            sql.append(" AND \"createdTime\" >= ?");
            args.add(c.getCreatedFrom());
        }
        if (c.getCreatedTo() != null) {
            sql.append(" AND \"createdTime\" <= ?");
            args.add(c.getCreatedTo());
        }
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /** Two-phase paginated search (count → page of ids → full rows with children). Mirrors Go Search. */
    public SearchResult search(SearchCriteria c, String tenantId, int page, int size, boolean includeDeleted) {
        try {
            StringBuilder where = new StringBuilder();
            List<Object> args = new ArrayList<>();
            buildWhere(c, tenantId, includeDeleted, where, args);

            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + T + where, Long.class, args.toArray());
            long totalCount = total == null ? 0 : total;
            if (totalCount == 0) {
                metrics.recordDbOperation("SELECT", T, true);
                return new SearchResult(new ArrayList<>(), 0);
            }

            int limit = size;
            int offset = (page - 1) * size;
            List<Object> idArgs = new ArrayList<>(args);
            idArgs.add(limit);
            idArgs.add(offset);
            List<String> ids = jdbc.queryForList(
                    "SELECT id FROM " + T + where + " ORDER BY \"createdTime\" DESC LIMIT ? OFFSET ?",
                    String.class, idArgs.toArray());
            if (ids.isEmpty()) {
                metrics.recordDbOperation("SELECT", T, true);
                return new SearchResult(new ArrayList<>(), totalCount);
            }

            List<Individual> individuals = jdbc.query(
                    "SELECT " + IND_COLS + " FROM " + T + " WHERE id IN (" + placeholders(ids.size())
                            + ") ORDER BY \"createdTime\" DESC",
                    individualMapper, ids.toArray());
            for (Individual ind : individuals) {
                loadChildren(ind);
            }
            metrics.recordDbOperation("SELECT", T, true);
            return new SearchResult(individuals, totalCount);
        } catch (RuntimeException e) {
            metrics.recordDbOperation("SELECT", T, false);
            throw e;
        }
    }

    /** True if at least one row matches (LIMIT 1). Mirrors Go Exists. */
    public boolean exists(SearchCriteria c, String tenantId, boolean includeDeleted) {
        try {
            StringBuilder where = new StringBuilder();
            List<Object> args = new ArrayList<>();
            buildWhere(c, tenantId, includeDeleted, where, args);
            List<String> ids = jdbc.queryForList(
                    "SELECT id FROM " + T + where + " LIMIT 1", String.class, args.toArray());
            metrics.recordDbOperation("SELECT", T, true);
            return !ids.isEmpty();
        } catch (RuntimeException e) {
            metrics.recordDbOperation("SELECT", T, false);
            throw e;
        }
    }

    public Individual findByMobileHash(String hash, String tenantId) {
        return firstActive("hashedmobilenumber = ?", hash, tenantId);
    }

    public Individual findByMobilePlain(String mobile, String tenantId) {
        return firstActive("mobilenumber = ?", mobile, tenantId);
    }

    private Individual firstActive(String predicate, String value, String tenantId) {
        try {
            List<Individual> rows = jdbc.query(
                    "SELECT " + IND_COLS + " FROM " + T + " WHERE " + predicate
                            + " AND tenantid = ? AND active = ? LIMIT 1",
                    individualMapper, value, tenantId, true);
            metrics.recordDbOperation("SELECT", T, true);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            metrics.recordDbOperation("SELECT", T, false);
            throw e;
        }
    }

    /**
     * Finds an active individual by one of its active identifiers, scoped by tenant, then reloads the
     * full record (with children) via {@link #findById}. Mirrors Go FindByIdentifier — joins the
     * identifier table to the individual table requiring both rows active. Returns null when no match.
     * (Uses the real v3 tables; Go's raw query referenced non-existent {@code _v1} tables.)
     */
    public Individual findByIdentifier(String identifierType, String identifierId, String tenantId) {
        try {
            List<String> individualIds = jdbc.queryForList(
                    "SELECT i.individualid FROM " + T_IDENT + " i JOIN " + T + " ind "
                            + "ON i.individualid = ind.id "
                            + "WHERE i.identifiertype = ? AND i.identifierid = ? AND ind.tenantid = ? "
                            + "AND i.active = ? AND ind.active = ? LIMIT 1",
                    String.class, identifierType, identifierId, tenantId, true, true);
            metrics.recordDbOperation("SELECT", T_IDENT, true);
            if (individualIds.isEmpty()) {
                return null;
            }
            return findById(individualIds.get(0), tenantId);
        } catch (RuntimeException e) {
            metrics.recordDbOperation("SELECT", T_IDENT, false);
            throw e;
        }
    }

    /** Finds an active individual by given+family name (case-insensitive). Mirrors Go FindByName. */
    public Individual findByName(String givenName, String familyName, String tenantId) {
        try {
            StringBuilder sql = new StringBuilder("SELECT " + IND_COLS + " FROM " + T
                    + " WHERE tenantid = ? AND active = ?");
            List<Object> args = new ArrayList<>();
            args.add(tenantId);
            args.add(true);
            if (givenName != null && !givenName.isEmpty()) {
                sql.append(" AND LOWER(givenname) = ?");
                args.add(givenName.toLowerCase());
            }
            if (familyName != null && !familyName.isEmpty()) {
                sql.append(" AND LOWER(familyname) = ?");
                args.add(familyName.toLowerCase());
            }
            sql.append(" LIMIT 1");
            List<Individual> rows = jdbc.query(sql.toString(), individualMapper, args.toArray());
            metrics.recordDbOperation("SELECT", T, true);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (EmptyResultDataAccessException e) {
            metrics.recordDbOperation("SELECT", T, true);
            return null;
        } catch (RuntimeException e) {
            metrics.recordDbOperation("SELECT", T, false);
            throw e;
        }
    }

    /** Paginated search result holder. */
    public record SearchResult(List<Individual> individuals, long totalCount) {}
}
