package com.example.warehouseManagement.Services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.warehouseManagement.Domains.Backorder;
import com.example.warehouseManagement.Domains.Item;
import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;
import com.example.warehouseManagement.Domains.PickingJobLine;
import com.example.warehouseManagement.Domains.SalesOrder;
import com.example.warehouseManagement.Domains.WarehouseSection;
import com.example.warehouseManagement.Domains.Exceptions.InvalidPickException;
import com.example.warehouseManagement.Domains.Exceptions.PickingJobAlreadyFulfilledException;
import com.example.warehouseManagement.Domains.DTOs.AdvancedPickingJobSearchCriteria;
import com.example.warehouseManagement.Domains.DTOs.PickingJobDto;
import com.example.warehouseManagement.Domains.DTOs.PickingJobLineDto;
import com.example.warehouseManagement.Domains.DTOs.TextMode;
import com.example.warehouseManagement.Repositories.BackorderRepository;
import com.example.warehouseManagement.Repositories.PickingJobLineRepository;
import com.example.warehouseManagement.Repositories.PickingJobRepository;
import com.example.warehouseManagement.Repositories.WarehouseSectionRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

@Service
public class PickingJobServiceImpl implements PickingJobService {
    private final PickingJobRepository pickingJobRepository;
    private final PickingJobLineRepository pickingJobLineRepository;
    private final WarehouseSectionRepository warehouseSectionRepository;
    private final StockService stockService;
    private final BackorderRepository backorderRepository;

    public PickingJobServiceImpl(PickingJobRepository pickingJobRepository,
            PickingJobLineRepository pickingJobLineRepository,
            WarehouseSectionRepository warehouseSectionRepository,
            StockService stockService,
            BackorderRepository backorderRepository) {
        this.pickingJobRepository = pickingJobRepository;
        this.pickingJobLineRepository = pickingJobLineRepository;
        this.warehouseSectionRepository = warehouseSectionRepository;
        this.stockService = stockService;
        this.backorderRepository = backorderRepository;
    }

    @Override
    public Iterable<PickingJob> findAll() {
        return pickingJobRepository.findAll();
    }

    @Override
    public Optional<PickingJob> findById(Long id) {
        return pickingJobRepository.findById(id);
    }

    @Override
    public PickingJob save(PickingJob pickingJob) {
        // return pickingJobRepository.save(pickingJob);
        PickingJob savedPickingJob = pickingJobRepository.save(pickingJob);
        for (PickingJobLine pickingJobLine : savedPickingJob.getPickingJobLines())
            pickingJobLine.setPickingJob(savedPickingJob);
        pickingJobLineRepository.saveAll(savedPickingJob.getPickingJobLines());
        return pickingJobRepository.save(savedPickingJob);
    }

    @Override
    public void delete(PickingJob pickingJob) {
        pickingJobRepository.delete(pickingJob);
    }

    @Override
    public Page<PickingJob> findAdvanced(AdvancedPickingJobSearchCriteria criteria, Pageable pageable) {
        return pickingJobRepository.findAll(buildSpec(criteria), pageable);
    }

    private static Specification<PickingJob> buildSpec(AdvancedPickingJobSearchCriteria c) {
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

            if (c.getSalesOrderId() != null && !c.getSalesOrderId().isBlank()) {
                String soStr = c.getSalesOrderId().trim();
                TextMode mode = c.getSalesOrderIdMode() == null ? TextMode.STARTS_WITH : c.getSalesOrderIdMode();
                if (mode == TextMode.EQUALS) {
                    try {
                        ps.add(cb.equal(root.get("salesOrder").get("id"), Long.parseLong(soStr)));
                    } catch (NumberFormatException ignored) {
                        ps.add(cb.disjunction());
                    }
                } else {
                    Expression<String> soText = root.get("salesOrder").<Long>get("id").as(String.class);
                    String pattern = (mode == TextMode.STARTS_WITH) ? soStr + "%" : "%" + soStr + "%";
                    ps.add(cb.like(soText, pattern));
                }
            }

            if (c.getCustomer() != null && !c.getCustomer().isBlank()) {
                String v = c.getCustomer().trim().toLowerCase();
                Expression<String> customerName = cb.lower(root.get("salesOrder").get("customer").get("name"));
                switch (c.getCustomerMode() == null ? TextMode.CONTAINS : c.getCustomerMode()) {
                    case EQUALS      -> ps.add(cb.equal(customerName, v));
                    case STARTS_WITH -> ps.add(cb.like(customerName, v + "%"));
                    case CONTAINS    -> ps.add(cb.like(customerName, "%" + v + "%"));
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

    /**
     * Fulfils a PENDING picking job.
     * <ul>
     * <li>Every line is processed exactly once and the line list is never
     * modified while iterating (unpicked lines stay on the job with qtyPicked 0).</li>
     * <li>picked = min(entered, quantity available in the chosen bin); the stock is
     * deducted here, in the same transaction, so a repeated call cannot deduct twice.</li>
     * <li>The unpicked remainder of each line goes to one new PENDING picking job and
     * is recorded as a single backorder per (sales order, item).</li>
     * </ul>
     * @throws PickingJobAlreadyFulfilledException if the job is not PENDING
     * @throws InvalidPickException for a malformed request, negative or excessive
     *         quantities, an unknown bin, or when nothing at all could be picked
     */
    @Override
    @Transactional
    public PickingJob fulfill(PickingJob pickingJob, PickingJobDto pickingJobDto) {
        if (pickingJob == null || pickingJobDto == null)
            throw new InvalidPickException("Picking job and pick details are required");
        // Reload so the status check sees the persisted state, not a stale detached copy
        PickingJob job = pickingJob.getId() == null ? pickingJob
                : pickingJobRepository.findById(pickingJob.getId()).orElse(pickingJob);
        if (!job.isPending())
            throw new PickingJobAlreadyFulfilledException(job.getId());

        List<PickingJobLine> lines = job.getPickingJobLines();
        List<PickingJobLineDto> dtoLines = pickingJobDto.getPickingJobDtoLines();
        if (dtoLines == null || dtoLines.size() != lines.size())
            throw new InvalidPickException("Pick details do not match the picking job lines");

        // Pair each line with its DTO entry: by line id when given, otherwise by position
        Map<Long, PickingJobLineDto> dtoById = new HashMap<>();
        for (PickingJobLineDto d : dtoLines)
            if (d != null && d.getPickingJobLineId() != null)
                dtoById.put(d.getPickingJobLineId(), d);

        // item id -> total unpicked remainder in this job
        Map<Long, Integer> remainderByItem = new LinkedHashMap<>();
        List<PickingJobLine> remainderLines = new ArrayList<>();
        int totalPicked = 0;

        for (int i = 0; i < lines.size(); i++) {
            PickingJobLine line = lines.get(i);
            PickingJobLineDto dto = dtoLines.get(i);
            if (line.getId() != null && dtoById.containsKey(line.getId()))
                dto = dtoById.get(line.getId());
            if (dto == null)
                throw new InvalidPickException("Missing pick details for line " + (i + 1));

            int requested = dto.getQtyPicked();
            if (requested < 0)
                throw new InvalidPickException("Picked quantity cannot be negative: " + requested);
            if (requested > line.getQtyToPick())
                throw new InvalidPickException("Picked quantity " + requested
                        + " exceeds quantity to pick " + line.getQtyToPick());

            Long sectionId = dto.getWareHouseSectionId();
            WarehouseSection section = null;
            int picked = 0;
            if (requested > 0 && sectionId != null && sectionId != 0L) {
                section = warehouseSectionRepository.findById(sectionId)
                        .orElseThrow(() -> new InvalidPickException("Unknown warehouse section " + sectionId));
                // Availability is read live, so earlier lines of the same job that drew from
                // the same bin are already accounted for
                picked = Math.min(requested, stockService.availableAt(sectionId, line.getItem().getId()));
                stockService.decreaseAt(sectionId, line.getItem().getId(), picked);
            }
            int remainder = line.recordPick(section, picked);
            totalPicked += picked;
            pickingJobLineRepository.save(line);

            if (remainder > 0) {
                remainderByItem.merge(line.getItem().getId(), remainder, Integer::sum);
                remainderLines.add(PickingJobLine.builder().item(line.getItem())
                        .qtyToPick(remainder).qtyPicked(0).build());
            }
        }

        if (totalPicked == 0)
            // Roll back: the job stays PENDING, it already represents the demand
            throw new InvalidPickException("Nothing could be picked for picking job " + job.getId());

        job.markFulfilled();
        PickingJob savedJob = pickingJobRepository.save(job);

        SalesOrder salesOrder = job.getSalesOrder();
        if (!remainderLines.isEmpty()) {
            PickingJob backorderJob = pickingJobRepository
                    .save(PickingJob.builder().salesOrder(salesOrder).build());
            for (PickingJobLine remainderLine : remainderLines) {
                remainderLine.setPickingJob(backorderJob);
                backorderJob.getPickingJobLines().add(pickingJobLineRepository.save(remainderLine));
            }
            pickingJobRepository.save(backorderJob);
        }
        syncBackorders(salesOrder, lines, remainderByItem);
        return savedJob;
    }

    /**
     * One backorder row per (sales order, item): set to this job's remainder,
     * created when absent, removed when the item is now fully picked.
     */
    private void syncBackorders(SalesOrder salesOrder, List<PickingJobLine> lines,
            Map<Long, Integer> remainderByItem) {
        if (salesOrder == null)
            return;
        Map<Long, Item> allItems = new LinkedHashMap<>();
        for (PickingJobLine line : lines)
            allItems.put(line.getItem().getId(), line.getItem());
        List<Backorder> existing = backorderRepository.findBySalesOrder(salesOrder);
        for (Map.Entry<Long, Item> e : allItems.entrySet()) {
            int remainder = remainderByItem.getOrDefault(e.getKey(), 0);
            Backorder current = existing.stream()
                    .filter(b -> b.getItem() != null && e.getKey().equals(b.getItem().getId()))
                    .findFirst().orElse(null);
            if (remainder > 0) {
                if (current == null) {
                    Backorder created = backorderRepository.save(
                            Backorder.builder().salesOrder(salesOrder).item(e.getValue()).qty(remainder).build());
                    salesOrder.getBackorders().add(created);
                } else {
                    current.changeQty(remainder);
                    backorderRepository.save(current);
                }
            } else if (current != null) {
                salesOrder.getBackorders().remove(current);
                backorderRepository.delete(current);
            }
        }
    }

    @Override
    public long countPending() {
        return pickingJobRepository.countByStatus(PjStatus.PENDING);
    }

}
