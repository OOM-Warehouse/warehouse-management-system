package com.example.warehouseManagement.Services;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.warehouseManagement.Domains.GoodsReceiptNote;
import com.example.warehouseManagement.Domains.GoodsReceiptNote.GrnStatus;
import com.example.warehouseManagement.Domains.GoodsReceiptNoteLine;
import com.example.warehouseManagement.Domains.Item;
import com.example.warehouseManagement.Domains.PurchaseOrder;
import com.example.warehouseManagement.Domains.PurchaseOrder.PoStatus;
import com.example.warehouseManagement.Domains.PurchaseOrderLine;
import com.example.warehouseManagement.Domains.Stock;
import com.example.warehouseManagement.Domains.WarehouseSection;
import com.example.warehouseManagement.Domains.DTOs.AdvancedGrnSearchCriteria;
import com.example.warehouseManagement.Domains.DTOs.GoodsReceiptNoteDto;
import com.example.warehouseManagement.Domains.DTOs.GoodsReceiptNoteLineDto;
import com.example.warehouseManagement.Domains.DTOs.TextMode;
import com.example.warehouseManagement.Domains.Exceptions.GoodsReceiptAlreadyFulfilledException;
import com.example.warehouseManagement.Domains.Exceptions.InvalidReceiptQuantityException;
import com.example.warehouseManagement.Repositories.GoodsReceiptNoteLineRepository;
import com.example.warehouseManagement.Repositories.GoodsReceiptNoteRepository;
import com.example.warehouseManagement.Repositories.StockRepository;
import com.example.warehouseManagement.Repositories.WarehouseSectionRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

@Service
public class GoodsReceiptNoteImpl implements GoodsReceiptNoteService {
    private static final String FLOOR_WH_SECTION = "00-00-0-0";
    private final GoodsReceiptNoteRepository goodsReceiptNoteRepository;
    private final GoodsReceiptNoteLineRepository goodsReceiptNoteLineRepository;
    private final StockRepository stockRepository;
    private final WarehouseSectionRepository warehouseSectionRepository;
    
    
    public GoodsReceiptNoteImpl(GoodsReceiptNoteRepository goodsReceiptNoteRepository,
            GoodsReceiptNoteLineRepository goodsReceiptNoteLineRepository,
            StockRepository stockRepository, WarehouseSectionRepository warehouseSectionRepository) { 
        this.goodsReceiptNoteRepository = goodsReceiptNoteRepository;
        this.goodsReceiptNoteLineRepository = goodsReceiptNoteLineRepository;
        this.stockRepository = stockRepository;
        this.warehouseSectionRepository = warehouseSectionRepository;
    }

    /**
     * Returns a goods receipt note by 
     */
    @Override
    public Optional<GoodsReceiptNote> findById(Long id) {
        return goodsReceiptNoteRepository.findById(id);
        
    }

    /**
     * Returns a Goods receipt note by a given purchase order number
     */
    @Override
    public Optional<GoodsReceiptNote> findByPurchaseOrder(Long purchaseOrderNumber) {
        return goodsReceiptNoteRepository.findFirstByPurchaseOrderIdAndStatusOrderByIdAsc(
                purchaseOrderNumber, GrnStatus.PENDING);
    }

    /**
     * Returns a list of all the goods receipt notes by a given vendor
     */
    @Override
    public List<GoodsReceiptNote> findAllPendingByVendor(Long vendorId) {
        return goodsReceiptNoteRepository.findAllPendingByVendor(vendorId);
    }

    /**
     * Returns a list of all goods receipt notes persisted in the dba
     */
    @Override
    public Iterable<GoodsReceiptNote> findAll() {
        return goodsReceiptNoteRepository.findAll();
    }

    /**
     * Persist a new Goods receipt note in the dba
     */
    @Override
    public GoodsReceiptNote save(GoodsReceiptNote goodsReceiptNote) {
        GoodsReceiptNote savedGoodsReceiptNote = goodsReceiptNoteRepository.save(goodsReceiptNote);
        goodsReceiptNoteLineRepository.saveAll(savedGoodsReceiptNote.getGoodsReceiptNoteLines());
        return goodsReceiptNoteRepository.save(savedGoodsReceiptNote);
    }

    /**
     * Deletes a given goods receipt note from the dba
     */
    @Override
    public void delete(GoodsReceiptNote goodsReceiptNote) {
        goodsReceiptNoteRepository.delete(goodsReceiptNote);
    }

    /**
     * Processes a receipt against a PENDING goods receipt note.
     * <ol>
     *   <li>A FULFILLED GRN / RECEIVED PO is rejected (no double inventory increase).</li>
     *   <li>Every posted quantity is validated first (non-negative, line count matches,
     *       cumulative received per item never exceeds the ordered quantity); nothing is
     *       written when any check fails.</li>
     *   <li>An all-zero receipt changes nothing and returns {@code null}.</li>
     *   <li>Otherwise the received quantities go to the floor section stock, the GRN is
     *       FULFILLED and the PO becomes RECEIVED (everything ordered has arrived) or
     *       PARTIALLY_RECEIVED; in the latter case a fresh PENDING GRN is opened for the rest.</li>
     * </ol>
     */
    @Override
    @Transactional
    public GoodsReceiptNote fulfill(GoodsReceiptNote goodsReceiptNote, GoodsReceiptNoteDto goodsReceiptNoteDto) {
        if (goodsReceiptNote.getStatus() == GrnStatus.FULFILLED) {
            throw new GoodsReceiptAlreadyFulfilledException(
                    "Goods receipt note " + goodsReceiptNote.getId() + " has already been fulfilled.");
        }
        PurchaseOrder purchaseOrder = goodsReceiptNote.getPurchaseOrder();
        if (purchaseOrder.getStatus() == PoStatus.RECEIVED) {
            throw new GoodsReceiptAlreadyFulfilledException(
                    "Purchase order " + purchaseOrder.getId() + " has already been fully received.");
        }
        List<GoodsReceiptNoteLine> grnLines = goodsReceiptNote.getGoodsReceiptNoteLines();
        List<GoodsReceiptNoteLineDto> dtoLines = goodsReceiptNoteDto == null
                ? null : goodsReceiptNoteDto.getGoodsReceiptNoteLines();
        if (dtoLines == null || dtoLines.size() != grnLines.size()) {
            throw new InvalidReceiptQuantityException(
                    "The receipt must contain exactly one quantity per goods receipt note line.");
        }

        // 1. Validate and total the quantities of this receipt per item
        Map<Long, Integer> receivingNow = new HashMap<>();
        int totalNow = 0;
        for (int i = 0; i < grnLines.size(); i++) {
            int qty = dtoLines.get(i).getQty();
            if (qty < 0) {
                throw new InvalidReceiptQuantityException(
                        "Received quantity cannot be negative (line " + (i + 1) + ").");
            }
            totalNow += qty;
            receivingNow.merge(grnLines.get(i).getItem().getId(), qty, Integer::sum);
        }
        if (totalNow == 0) return null;

        Map<Long, Integer> ordered = orderedQuantities(purchaseOrder);
        Map<Long, Integer> alreadyReceived = receivedQuantities(purchaseOrder, goodsReceiptNote);
        for (Map.Entry<Long, Integer> e : receivingNow.entrySet()) {
            int orderedQty = ordered.getOrDefault(e.getKey(), 0);
            int cumulative = alreadyReceived.getOrDefault(e.getKey(), 0) + e.getValue();
            if (e.getValue() > 0 && cumulative > orderedQty) {
                throw new InvalidReceiptQuantityException("Received quantity for item " + e.getKey()
                        + " (" + cumulative + " in total) exceeds the ordered quantity (" + orderedQty + ").");
            }
        }

        // 2. Record the receipt. Receiving department puts goods on the "floor" section first
        // (put-away later moves them to their racks).
        WarehouseSection floor = warehouseSectionRepository.findBySectionNumber(FLOOR_WH_SECTION)
                .orElseThrow(() -> new IllegalStateException(
                        "Floor warehouse section " + FLOOR_WH_SECTION + " is not configured."));
        for (int i = 0; i < grnLines.size(); i++) {
            GoodsReceiptNoteLine line = grnLines.get(i);
            GoodsReceiptNoteLineDto lineDto = dtoLines.get(i);
            int qty = lineDto.getQty();
            line.setQty(qty);
            line.setNotes(lineDto.getNotes());
            goodsReceiptNoteLineRepository.save(line);
            if (qty > 0) addToFloorStock(line.getItem(), floor, qty);
        }
        goodsReceiptNote.setStatus(GrnStatus.FULFILLED);
        GoodsReceiptNote saved = goodsReceiptNoteRepository.save(goodsReceiptNote);

        // 3. Derive the PO status from cumulative quantities (not from line counts)
        boolean complete = true;
        for (Map.Entry<Long, Integer> e : ordered.entrySet()) {
            int received = alreadyReceived.getOrDefault(e.getKey(), 0) + receivingNow.getOrDefault(e.getKey(), 0);
            if (received < e.getValue()) {
                complete = false;
                break;
            }
        }
        purchaseOrder.setStatus(complete ? PoStatus.RECEIVED : PoStatus.PARTIALLY_RECEIVED);
        if (!complete) findOrCreatePending(purchaseOrder);
        return saved;
    }

    /** Σ ordered qty per item id over the PO lines. */
    private static Map<Long, Integer> orderedQuantities(PurchaseOrder purchaseOrder) {
        Map<Long, Integer> ordered = new HashMap<>();
        for (PurchaseOrderLine pol : purchaseOrder.getPurchaseOrderLines()) {
            ordered.merge(pol.getItem().getId(), pol.getQty(), Integer::sum);
        }
        return ordered;
    }

    /** Σ received qty per item id over the PO's FULFILLED GRNs (excluding the given one). */
    private Map<Long, Integer> receivedQuantities(PurchaseOrder purchaseOrder, GoodsReceiptNote exclude) {
        Map<Long, Integer> received = new HashMap<>();
        for (GoodsReceiptNote grn : goodsReceiptNoteRepository.findAllByPurchaseOrderId(purchaseOrder.getId())) {
            if (grn.getStatus() != GrnStatus.FULFILLED || grn.getId().equals(exclude.getId())) continue;
            for (GoodsReceiptNoteLine l : grn.getGoodsReceiptNoteLines()) {
                received.merge(l.getItem().getId(), l.getQty(), Integer::sum);
            }
        }
        return received;
    }

    /** Adds to the existing floor stock row of the item, or creates one. */
    private void addToFloorStock(Item item, WarehouseSection floor, int qty) {
        Stock stock = stockRepository.findByItem(item).stream()
                .filter(s -> s.getWarehouseSection() != null
                        && Objects.equals(s.getWarehouseSection().getId(), floor.getId()))
                .findFirst()
                .orElseGet(() -> Stock.builder().item(item).warehouseSection(floor).qtyOnHand(0).build());
        stock.setQtyOnHand(stock.getQtyOnHand() + qty);
        stockRepository.save(stock);
    }

    @Override
    public Page<GoodsReceiptNote> findAdvanced(AdvancedGrnSearchCriteria criteria, Pageable pageable) {
        return goodsReceiptNoteRepository.findAll(buildSpec(criteria), pageable);
    }

    private static Specification<GoodsReceiptNote> buildSpec(AdvancedGrnSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (c.getId() != null && !c.getId().isBlank()) {
                String idStr = c.getId().trim();
                TextMode mode = c.getIdMode() == null ? TextMode.STARTS_WITH : c.getIdMode();
                if (mode == TextMode.EQUALS) {
                    try {
                        ps.add(cb.equal(root.get("id"), Long.parseLong(idStr)));
                    } catch (NumberFormatException ignored) {
                        ps.add(cb.disjunction());
                    }
                } else {
                    Expression<String> idText = root.<Long>get("id").as(String.class);
                    String pattern = (mode == TextMode.STARTS_WITH) ? idStr + "%" : "%" + idStr + "%";
                    ps.add(cb.like(idText, pattern));
                }
            }

            if (c.getPurchaseOrderId() != null && !c.getPurchaseOrderId().isBlank()) {
                String poStr = c.getPurchaseOrderId().trim();
                TextMode mode = c.getPurchaseOrderIdMode() == null ? TextMode.STARTS_WITH : c.getPurchaseOrderIdMode();
                if (mode == TextMode.EQUALS) {
                    try {
                        ps.add(cb.equal(root.get("purchaseOrder").get("id"), Long.parseLong(poStr)));
                    } catch (NumberFormatException ignored) {
                        ps.add(cb.disjunction());
                    }
                } else {
                    Expression<String> poText = root.get("purchaseOrder").<Long>get("id").as(String.class);
                    String pattern = (mode == TextMode.STARTS_WITH) ? poStr + "%" : "%" + poStr + "%";
                    ps.add(cb.like(poText, pattern));
                }
            }

            if (c.getVendor() != null && !c.getVendor().isBlank()) {
                String v = c.getVendor().trim().toLowerCase();
                Expression<String> vendorName = cb.lower(root.get("purchaseOrder").get("vendor").get("name"));
                switch (c.getVendorMode() == null ? TextMode.CONTAINS : c.getVendorMode()) {
                    case EQUALS      -> ps.add(cb.equal(vendorName, v));
                    case STARTS_WITH -> ps.add(cb.like(vendorName, v + "%"));
                    case CONTAINS    -> ps.add(cb.like(vendorName, "%" + v + "%"));
                }
            }

            if (c.getDateFrom() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("date"), c.getDateFrom()));
            }
            if (c.getDateTo() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("date"), c.getDateTo()));
            }
            if (c.getStatus() != null) {
                ps.add(cb.equal(root.get("status"), c.getStatus()));
            }

            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(Predicate[]::new));
        };
    }

    @Override
    @Transactional
    public GoodsReceiptNote findOrCreatePending(PurchaseOrder purchaseOrder) {
        Optional<GoodsReceiptNote> pending = findByPurchaseOrder(purchaseOrder.getId());
        return pending.isPresent() ? pending.get() : createNew(purchaseOrder);
    }

    @Override
    @Transactional
    public GoodsReceiptNote create(PurchaseOrder purchaseOrder) {
        return findOrCreatePending(purchaseOrder);
    }

    private GoodsReceiptNote createNew(PurchaseOrder purchaseOrder) {
        GoodsReceiptNote goodsReceiptNote = GoodsReceiptNote.builder()
                    .purchaseOrder(purchaseOrder).build();
            // Create and persist a new Good receipt note
            GoodsReceiptNote savedGoodsReceiptNote = goodsReceiptNoteRepository.save(goodsReceiptNote);
            // Create and persist the goods receipt note lines from po
            for (PurchaseOrderLine purchaseOrderLine : purchaseOrder.getPurchaseOrderLines()) {
                // where each po line items will be stored
                GoodsReceiptNoteLine newGoodsReceiptNoteLine = GoodsReceiptNoteLine.builder()
                        .goodsReceiptNote(savedGoodsReceiptNote)
                        .item(purchaseOrderLine.getItem())
                        .qty(0).build();
                GoodsReceiptNoteLine saveGoodsReceiptNoteLine = goodsReceiptNoteLineRepository.save(newGoodsReceiptNoteLine);
                goodsReceiptNote.getGoodsReceiptNoteLines().add(saveGoodsReceiptNoteLine);
            }
        return goodsReceiptNoteRepository.save(savedGoodsReceiptNote);
    }

    public GoodsReceiptNoteDto addGoodReceiptNoteLines(GoodsReceiptNote goodsReceiptNote) {
        GoodsReceiptNoteDto goodsReceiptNoteDto = GoodsReceiptNoteDto.builder()
            .date(LocalDate.now())
            .purchaseOrderId(goodsReceiptNote.getPurchaseOrder().getId()).build();
        for (int i=0; i<goodsReceiptNote.getGoodsReceiptNoteLines().size(); i++) {
            goodsReceiptNoteDto.getGoodsReceiptNoteLines().add(new GoodsReceiptNoteLineDto());
        }
        return goodsReceiptNoteDto;
    }

    @Override
    public long countPending() {
        return goodsReceiptNoteRepository.countByStatus(GrnStatus.PENDING);
    }

}
