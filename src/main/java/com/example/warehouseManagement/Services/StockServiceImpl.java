package com.example.warehouseManagement.Services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.PickingJobLine;
import com.example.warehouseManagement.Domains.Stock;
import com.example.warehouseManagement.Domains.Exceptions.InvalidPickException;
import com.example.warehouseManagement.Domains.WarehouseSection;
import com.example.warehouseManagement.Domains.DTOs.InventorySnapshotDto;
import com.example.warehouseManagement.Domains.DTOs.PutAwayTaskDto;
import com.example.warehouseManagement.Domains.DTOs.PutAwayTasksDtoWrapper;
import com.example.warehouseManagement.Domains.DTOs.ReorderItemDto;
import com.example.warehouseManagement.Domains.DTOs.StockLevelReportItemDto;
import com.example.warehouseManagement.Domains.DTOs.TopFiveMoversDto;
import com.example.warehouseManagement.Repositories.StockRepository;
import com.example.warehouseManagement.Repositories.WarehouseSectionRepository;

@Service
public class StockServiceImpl implements StockService {
    private final StockRepository stockRepository;
    private final WarehouseSectionRepository warehouseSectionRepository;

    public StockServiceImpl(StockRepository stockRepository, WarehouseSectionRepository warehouseSectionRepository) {
        this.stockRepository = stockRepository;
        this.warehouseSectionRepository = warehouseSectionRepository;
    }

    @Override
    public List<TopFiveMoversDto> getTopFiveMovers() {
        return stockRepository.getTopFiveMovers();
    }

    @Override
    public List<StockLevelReportItemDto> findStockReportsItemsByVendorId(Long vendorId) {
        return stockRepository.findStockReportsItemsByVendorId(vendorId);
    }

    @Override
    @Transactional
    public void pickStock(PickingJob pickingJob) {
        for (PickingJobLine line : pickingJob.getPickingJobLines()) {
            if (line.getQtyPicked() <= 0 || line.getWarehouseSection() == null)
                continue; // nothing was picked on this line
            decreaseAt(line.getWarehouseSection().getId(), line.getItem().getId(), line.getQtyPicked());
        }
    }

    @Override
    public int availableAt(Long warehouseSectionId, Long itemId) {
        int total = 0;
        for (Stock stock : stockRepository.findByWarehouseSectionIdAndItemIdOrderByIdAsc(warehouseSectionId, itemId))
            total += stock.getQtyOnHand();
        return total;
    }

    @Override
    @Transactional
    public void decreaseAt(Long warehouseSectionId, Long itemId, int qty) {
        if (qty < 0)
            throw new InvalidPickException("Quantity to remove cannot be negative: " + qty);
        if (qty == 0)
            return;
        List<Stock> rows = stockRepository.findByWarehouseSectionIdAndItemIdOrderByIdAsc(warehouseSectionId, itemId);
        int total = rows.stream().mapToInt(Stock::getQtyOnHand).sum();
        if (qty > total)
            throw new InvalidPickException("Insufficient stock: requested " + qty + " but only " + total + " on hand");
        int remaining = qty;
        for (Stock row : rows) {
            if (remaining == 0)
                break;
            int take = Math.min(remaining, row.getQtyOnHand());
            row.decrease(take);
            remaining -= take;
            if (row.getQtyOnHand() == 0)
                stockRepository.delete(row);
            else
                stockRepository.save(row);
        }
    }

    @Override
    public List<Stock> findStocksOnFloor() {
        return stockRepository.findStockOnFloor();
    }

    @Override
    public List<PutAwayTaskDto> generatePutAwayTaskDtoByWarehouseSection(WarehouseSection warehouseSection) {
        List<PutAwayTaskDto> putAwayTaskDtos = new ArrayList<>();
        for (Stock stock : warehouseSection.getStocks()) {
            PutAwayTaskDto putAwayTaskDto = PutAwayTaskDto.builder()
                    .destinationWarehouseSectionId(stock.getWarehouseSection().getId())
                    .qtyToMove(0)
                    .build();
            putAwayTaskDtos.add(putAwayTaskDto);
        }
        return putAwayTaskDtos;
    }

    @Override
    public Optional<Stock> findById(Long id) {
        return stockRepository.findById(id);
    }

    @Override
    public Stock save(Stock stock) {
        return stockRepository.save(stock);
    }

    @Override
    @Transactional
    public void putAwayStocks(PutAwayTasksDtoWrapper wrapper, List<Stock> stocks) {
        for (int i=0; i<wrapper.getPutAwayTasks().size(); i++) {
            PutAwayTaskDto putAwayTaskDto = wrapper.getPutAwayTasks().get(i);
            Stock stock = stocks.get(i);
            if (putAwayTaskDto.getQtyToMove() == 0) continue;
            Long destinationWarehouseSectionId = putAwayTaskDto.getDestinationWarehouseSectionId();
            int qtyMoved = putAwayTaskDto.getQtyToMove();
            Optional<Stock> movedStock = stockRepository.findById(stock.getId());
            if (movedStock.isPresent()) {
                Optional<WarehouseSection> destination = warehouseSectionRepository
                            .findById(destinationWarehouseSectionId);
                if (qtyMoved >= movedStock.get().getQtyOnHand()) {
                    // move all stocks to new destination bin
                    movedStock.get().setWarehouseSection(destination.get());
                    stockRepository.save(movedStock.get());
                } else {
                    // move part of the stocks to a new destination
                    Stock newStock = Stock.builder()
                        .item(movedStock.get().getItem())
                        .qtyOnHand(qtyMoved)
                        .warehouseSection(destination.get())
                        .build();
                    movedStock.get().decrease(qtyMoved, "put-away split to " + destination.get().getSectionNumber());
                    stockRepository.save(movedStock.get());
                    stockRepository.save(newStock);
                }
            }
        }
    }

    @Override
    public List<Stock> findStocksByWareHouseSectionNumber(String warehouseSectionNumber) {
       return stockRepository.findStocksByWarehouseSectionNumber(warehouseSectionNumber);
    }

    @Override
    public InventorySnapshotDto findInventorySnapshot() {
        return stockRepository.findInventorySnapshot();
    }

    @Override
    public List<ReorderItemDto> findReorderCandidates() {
        return stockRepository.findReorderCandidates();
    }

    @Override
    public long countStockOnFloor() {
        return stockRepository.findStockOnFloor().size();
    }

}
