package com.digit.individual.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity ↔ DTO mappers. Mirrors the Go mapper functions in internal/models/*_dto.go.
 * Date parsing accepts {@code YYYY-MM-DD} and RFC3339; output is always {@code YYYY-MM-DD}.
 */
public final class ModelMappers {
    private ModelMappers() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Flexible parse: accepts YYYY-MM-DD (preferred) or full RFC3339. Returns null when blank/unparseable. */
    public static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty() || "null".equals(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s, DATE);
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(s).toLocalDate();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid date: " + s);
        }
    }

    public static String formatDate(LocalDate d) {
        return d == null ? null : d.format(DATE);
    }

    // ---------------------------------------------------------------- Individual

    public static Individual toEntity(IndividualDTO d) {
        if (d == null) {
            return null;
        }
        Individual e = new Individual();
        e.setId(d.getId());
        e.setIndividualId(d.getIndividualId());
        e.setGivenName(d.getGivenName());
        e.setFamilyName(d.getFamilyName());
        e.setOtherNames(d.getOtherNames());
        e.setDateOfBirth(parseDate(d.getDateOfBirth()));
        e.setGender(d.getGender());
        e.setAge(d.getAge());
        e.setMobileNumber(d.getMobileNumber());
        e.setMobileNumberVerified(d.isMobileNumberVerified());
        e.setAltContactNumber(d.getAltContactNumber());
        e.setEmail(d.getEmail());
        e.setEmailVerified(d.isEmailVerified());
        e.setLocale(d.getLocale());
        e.setActive(d.isActive());
        e.setFatherName(d.getFatherName());
        e.setHusbandName(d.getHusbandName());
        e.setPhoto(d.getPhoto());
        e.setUserId(d.getUserId());
        e.setAdditionalDetails(d.getAdditionalAttributes());
        e.setRowVersion(d.getVersion());
        e.setRequestId(d.getRequestId());
        List<Address> addrs = new ArrayList<>();
        if (d.getAddresses() != null) {
            for (AddressDTO a : d.getAddresses()) {
                Address ae = toEntity(a);
                if (ae != null) {
                    addrs.add(ae);
                }
            }
        }
        e.setAddresses(addrs);
        List<Identifier> ids = new ArrayList<>();
        if (d.getIdentifiers() != null) {
            for (IdentifierDTO i : d.getIdentifiers()) {
                Identifier ie = toEntity(i);
                if (ie != null) {
                    ids.add(ie);
                }
            }
        }
        e.setIdentifiers(ids);
        List<Document> docs = new ArrayList<>();
        if (d.getDocuments() != null) {
            for (DocumentDTO doc : d.getDocuments()) {
                Document de = toEntity(doc);
                if (de != null) {
                    docs.add(de);
                }
            }
        }
        e.setDocuments(docs);
        return e;
    }

    public static IndividualDTO toDto(Individual e) {
        if (e == null) {
            return null;
        }
        IndividualDTO d = new IndividualDTO();
        d.setId(e.getId());
        d.setIndividualId(e.getIndividualId());
        d.setGivenName(e.getGivenName());
        d.setFamilyName(e.getFamilyName());
        d.setOtherNames(e.getOtherNames());
        d.setDateOfBirth(formatDate(e.getDateOfBirth()));
        d.setGender(e.getGender());
        d.setAge(e.getAge());
        d.setMobileNumber(e.getMobileNumber());
        d.setMobileNumberVerified(e.isMobileNumberVerified());
        d.setAltContactNumber(e.getAltContactNumber());
        d.setEmail(e.getEmail());
        d.setEmailVerified(e.isEmailVerified());
        d.setLocale(e.getLocale());
        d.setActive(e.isActive());
        d.setFatherName(e.getFatherName());
        d.setHusbandName(e.getHusbandName());
        d.setPhoto(e.getPhoto());
        d.setUserId(e.getUserId());
        d.setAdditionalAttributes(e.getAdditionalDetails());
        d.setVersion(e.getRowVersion());
        d.setRequestId(e.getRequestId());
        d.setAuditDetail(AuditDetail.of(e.getCreatedBy(), e.getModifiedBy(), e.getCreatedTime(), e.getModifiedTime()));
        if (e.getAddresses() != null && !e.getAddresses().isEmpty()) {
            List<AddressDTO> out = new ArrayList<>();
            for (Address a : e.getAddresses()) {
                AddressDTO ad = toDto(a);
                if (ad != null) {
                    out.add(ad);
                }
            }
            d.setAddresses(out);
        }
        if (e.getIdentifiers() != null && !e.getIdentifiers().isEmpty()) {
            List<IdentifierDTO> out = new ArrayList<>();
            for (Identifier i : e.getIdentifiers()) {
                IdentifierDTO id = toDto(i);
                if (id != null) {
                    out.add(id);
                }
            }
            d.setIdentifiers(out);
        }
        if (e.getDocuments() != null && !e.getDocuments().isEmpty()) {
            List<DocumentDTO> out = new ArrayList<>();
            for (Document doc : e.getDocuments()) {
                DocumentDTO dd = toDto(doc);
                if (dd != null) {
                    out.add(dd);
                }
            }
            d.setDocuments(out);
        }
        return d;
    }

    public static List<IndividualDTO> toDtoList(List<Individual> es) {
        List<IndividualDTO> out = new ArrayList<>(es.size());
        for (Individual e : es) {
            IndividualDTO d = toDto(e);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Address

    public static Address toEntity(AddressDTO d) {
        if (d == null) {
            return null;
        }
        Address e = new Address();
        e.setId(d.getId());
        e.setType(d.getType());
        e.setDoorNo(d.getDoorNo());
        e.setBuildingName(d.getBuildingName());
        e.setStreet(d.getStreet());
        e.setLandmark(d.getLandmark());
        e.setAddressLine1(d.getAddressLine1());
        e.setAddressLine2(d.getAddressLine2());
        e.setCity(d.getCity());
        e.setRegion(d.getRegion());
        e.setCountry(d.getCountry());
        e.setPincode(d.getPincode());
        e.setBoundaryCode(d.getBoundaryCode());
        e.setLatitude(d.getLatitude());
        e.setLongitude(d.getLongitude());
        e.setLocationAccuracy(d.getLocationAccuracy());
        return e;
    }

    public static AddressDTO toDto(Address e) {
        if (e == null) {
            return null;
        }
        AddressDTO d = new AddressDTO();
        d.setId(e.getId());
        d.setType(e.getType());
        d.setDoorNo(e.getDoorNo());
        d.setBuildingName(e.getBuildingName());
        d.setStreet(e.getStreet());
        d.setLandmark(e.getLandmark());
        d.setAddressLine1(e.getAddressLine1());
        d.setAddressLine2(e.getAddressLine2());
        d.setCity(e.getCity());
        d.setRegion(e.getRegion());
        d.setCountry(e.getCountry());
        d.setPincode(e.getPincode());
        d.setBoundaryCode(e.getBoundaryCode());
        d.setLatitude(e.getLatitude());
        d.setLongitude(e.getLongitude());
        d.setLocationAccuracy(e.getLocationAccuracy());
        d.setRequestId(e.getRequestId());
        d.setAuditDetail(AuditDetail.of(e.getCreatedBy(), e.getModifiedBy(), e.getCreatedTime(), e.getModifiedTime()));
        return d;
    }

    // ---------------------------------------------------------------- Identifier

    public static Identifier toEntity(IdentifierDTO d) {
        if (d == null) {
            return null;
        }
        Identifier e = new Identifier();
        e.setId(d.getId());
        e.setIdentifierType(d.getIdentifierType());
        e.setIdentifierId(d.getIdentifierId());
        e.setVerified(d.isVerified());
        e.setDocumentType(d.getDocumentType());
        e.setFileStoreId(d.getFileStoreId());
        e.setActive(d.isActive());
        return e;
    }

    public static IdentifierDTO toDto(Identifier e) {
        if (e == null) {
            return null;
        }
        IdentifierDTO d = new IdentifierDTO();
        d.setId(e.getId());
        d.setIndividualId(e.getIndividualId());
        d.setIdentifierType(e.getIdentifierType());
        d.setIdentifierId(e.getIdentifierId());
        d.setVerified(e.isVerified());
        d.setDocumentType(e.getDocumentType());
        d.setFileStoreId(e.getFileStoreId());
        d.setActive(e.isActive());
        d.setRequestId(e.getRequestId());
        d.setAuditDetail(AuditDetail.of(e.getCreatedBy(), e.getModifiedBy(), e.getCreatedTime(), e.getModifiedTime()));
        return d;
    }

    // ---------------------------------------------------------------- Document

    public static Document toEntity(DocumentDTO d) {
        if (d == null) {
            return null;
        }
        Document e = new Document();
        e.setId(d.getId());
        e.setDocumentType(d.getDocumentType());
        e.setFileStoreId(d.getFileStoreId());
        e.setDocumentUid(d.getDocumentUid());
        return e;
    }

    public static DocumentDTO toDto(Document e) {
        if (e == null) {
            return null;
        }
        DocumentDTO d = new DocumentDTO();
        d.setId(e.getId());
        d.setDocumentType(e.getDocumentType());
        d.setFileStoreId(e.getFileStoreId());
        d.setDocumentUid(e.getDocumentUid());
        d.setRequestId(e.getRequestId());
        d.setAuditDetail(AuditDetail.of(e.getCreatedBy(), e.getModifiedBy(), e.getCreatedTime(), e.getModifiedTime()));
        return d;
    }
}
