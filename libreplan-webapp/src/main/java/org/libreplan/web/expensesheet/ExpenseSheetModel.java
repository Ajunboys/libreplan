/*
 * This file is part of LibrePlan
 *
 * Copyright (C) 2012 WirelessGalicia, S.L.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.libreplan.web.expensesheet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.lang3.Validate;
import org.hibernate.Hibernate;
import org.joda.time.LocalDate;
import org.libreplan.business.common.IntegrationEntity;
import org.libreplan.business.common.daos.IConfigurationDAO;
import org.libreplan.business.common.entities.EntityNameEnum;
import org.libreplan.business.common.exceptions.InstanceNotFoundException;
import org.libreplan.business.expensesheet.daos.IExpenseSheetDAO;
import org.libreplan.business.expensesheet.entities.ExpenseSheet;
import org.libreplan.business.expensesheet.entities.ExpenseSheetLine;
import org.libreplan.business.expensesheet.entities.ExpenseSheetLineComparator;
import org.libreplan.business.orders.daos.IOrderDAO;
import org.libreplan.business.orders.daos.ISumExpensesDAO;
import org.libreplan.business.orders.entities.Order;
import org.libreplan.business.orders.entities.OrderElement;
import org.libreplan.business.orders.entities.OrderLineGroup;
import org.libreplan.business.resources.entities.Resource;
import org.libreplan.business.users.entities.User;
import org.libreplan.web.UserUtil;
import org.libreplan.web.common.IntegrationEntityModel;
import org.libreplan.web.common.concurrentdetection.OnConcurrentModification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model for UI operations related to {@link ExpenseSheet}.
 *
 * @author Susana Montes Pedreira <smontes@wirelessgalicia.com>
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
@OnConcurrentModification(goToPage = "/expensesheet/expenseSheet.zul")
public class ExpenseSheetModel extends IntegrationEntityModel implements IExpenseSheetModel {

    @Autowired
    private IOrderDAO orderDAO;

    @Autowired
    private IExpenseSheetDAO expenseSheetDAO;

    @Autowired
    private IConfigurationDAO configurationDAO;

    @Autowired
    private ISumExpensesDAO sumExpensesDAO;

    private ExpenseSheet expenseSheet;

    private ExpenseSheetLine newExpenseSheetLine;

    private List<Order> activeOrders = new ArrayList<>();

    private List<OrderElement> allActiveOrdersChildren = new ArrayList<>();

    private Order selectedProject;

    private Set<ExpenseSheetLine> deletedExpenseSheetLinesSet = new HashSet<>();

    private Resource resource;

    public void setExpenseSheet(ExpenseSheet expenseSheet) {
        this.expenseSheet = expenseSheet;
    }

    @Override
    public ExpenseSheet getExpenseSheet() {
        return expenseSheet;
    }

    @Override
    @Transactional
    public void confirmSave() {
        sumExpensesDAO.updateRelatedSumExpensesWithDeletedExpenseSheetLineSet(deletedExpenseSheetLinesSet);
        sumExpensesDAO.updateRelatedSumExpensesWithExpenseSheetLineSet(getExpenseSheet().getExpenseSheetLines());

        updateCalculatedFields(getExpenseSheet());
        expenseSheetDAO.save(getExpenseSheet());
        dontPoseAsTransientAndChildrenObjects(getExpenseSheet());
    }

    private void dontPoseAsTransientAndChildrenObjects(ExpenseSheet expenseSheet) {
        expenseSheet.dontPoseAsTransientObjectAnymore();
        for (ExpenseSheetLine expenseSheetLine : expenseSheet.getExpenseSheetLines()) {
            expenseSheetLine.dontPoseAsTransientObjectAnymore();
        }
    }

    @Override
    @Transactional
    public void generateExpenseSheetLineCodesIfIsNecessary() {
        if (expenseSheet.isCodeAutogenerated())
            generateExpenseSheetLineCodes();
    }

    private void generateExpenseSheetLineCodes() {
        expenseSheet.generateExpenseSheetLineCodes(getNumberOfDigitsCode());
    }

    private void updateCalculatedFields(ExpenseSheet expenseSheet) {
        expenseSheet.updateCalculatedProperties();
    }

    @Override
    public void prepareToList() {
        this.expenseSheet = null;
    }

    @Override
    @Transactional(readOnly = true)
    public void initCreate(boolean personal) {
        this.setSelectedProject(null);
        createNewExpenseSheetLine();
        this.expenseSheet = ExpenseSheet.create();

        this.expenseSheet.setCodeAutogenerated(configurationDAO.getConfiguration().getGenerateCodeForExpenseSheets());

        if (!this.expenseSheet.isCodeAutogenerated())
            this.expenseSheet.setCode("");
        else
            setDefaultCode();

        deletedExpenseSheetLinesSet = new HashSet<>();

        expenseSheet.setPersonal(personal);
        resource = initResource();
    }

    private Resource initResource() {
        if (expenseSheet.isNotPersonal())
            return null;


        SortedSet<ExpenseSheetLine> expenseSheetLines = expenseSheet.getExpenseSheetLines();

        if (!expenseSheetLines.isEmpty())
            return expenseSheetLines.iterator().next().getResource();


        User user = UserUtil.getUserFromSession();

        return user.isBound() ? user.getWorker() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public void prepareToEdit(ExpenseSheet expenseSheet) {
        this.setSelectedProject(null);
        createNewExpenseSheetLine();
        Validate.notNull(expenseSheet);
        this.expenseSheet = getFromDB(expenseSheet);
        initOldCodes();
        deletedExpenseSheetLinesSet = new HashSet<>();
        resource = initResource();
    }

    @Transactional(readOnly = true)
    private ExpenseSheet getFromDB(ExpenseSheet expenseSheet) {
        expenseSheetDAO.reattach(expenseSheet);
        forceLoadExpenseSheetData(expenseSheet);

        return expenseSheet;
    }

    private void forceLoadExpenseSheetData(ExpenseSheet expenseSheet) {
        expenseSheet.getTotal();
        for (ExpenseSheetLine line : expenseSheet.getExpenseSheetLines()) {
            forceLoadExpenseSheetLineData(line);
        }
    }

    private void forceLoadExpenseSheetLineData(ExpenseSheetLine line) {
        line.getCode();

        if (line.getResource() != null)
            line.getResource().getName();

        initializeOrderElement(line.getOrderElement());
    }

    private void initializeOrderElement(OrderElement orderElement) {
        Hibernate.initialize(orderElement);
        initializeOrder(orderElement);
    }

    private void initializeOrder(OrderElement orderElement) {
        OrderLineGroup parent = orderElement.getParent();
        while (parent != null) {
            Hibernate.initialize(parent);
            parent = parent.getParent();
        }
    }

    private void createNewExpenseSheetLine() {
        this.newExpenseSheetLine = ExpenseSheetLine.create(new BigDecimal(0), "", new LocalDate(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseSheet> getExpenseSheets() {
        return expenseSheetDAO != null ? expenseSheetDAO.list(ExpenseSheet.class) : new ArrayList<>();
    }

    @Override
    @Transactional
    public void removeExpenseSheet(ExpenseSheet expenseSheet) throws InstanceNotFoundException {
        Validate.notNull(expenseSheet);
        expenseSheet = getFromDB(expenseSheet);

        sumExpensesDAO.updateRelatedSumExpensesWithDeletedExpenseSheetLineSet(expenseSheet.getExpenseSheetLines());
        expenseSheetDAO.remove(expenseSheet.getId());
    }

    @Override
    public SortedSet<ExpenseSheetLine> getExpenseSheetLines() {
        return getExpenseSheet() != null
                ? getExpenseSheet().getExpenseSheetLines()
                : new TreeSet<>(new ExpenseSheetLineComparator());
    }

    @Override
    public void removeExpenseSheetLine(ExpenseSheetLine expenseSheetLine) {
        if (getExpenseSheet() != null) {
            deletedExpenseSheetLinesSet.add(expenseSheetLine);
            getExpenseSheet().remove(expenseSheetLine);
            expenseSheetLine.setExpenseSheet(null);
        }
    }

    @Override
    public void addExpenseSheetLine() {
        if (expenseSheet != null) {
            ExpenseSheetLine line = this.getNewExpenseSheetLine();
            line.setExpenseSheet(expenseSheet);

            if (expenseSheet.isPersonal())
                line.setResource(resource);

            expenseSheet.add(line);
        }
        this.createNewExpenseSheetLine();
    }

    @Override
    public ExpenseSheetLine getNewExpenseSheetLine() {
        return newExpenseSheetLine;
    }

    @Override
    public EntityNameEnum getEntityName() {
        return EntityNameEnum.EXPENSE_SHEET;
    }

    @Override
    public Set<IntegrationEntity> getChildren() {
        return (Set<IntegrationEntity>) (getExpenseSheet() != null
                ? getExpenseSheet().getExpenseSheetLines()
                : new HashSet<IntegrationEntity>());
    }

    @Override
    public IntegrationEntity getCurrentEntity() {
        return this.expenseSheet;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrders() {
        activeOrders = new ArrayList<>();
        if (orderDAO != null) {
            this.activeOrders = orderDAO.getActiveOrders();
            loadOrdersData();
        }
        return activeOrders;
    }

    private void loadOrdersData() {
        allActiveOrdersChildren = new ArrayList<>();
        for (Order order : activeOrders) {
            allActiveOrdersChildren.addAll(order.getAllChildren());
            order.getAllChildren().size();
        }
    }

    public List<OrderElement> getTasks() {
        return this.selectedProject == null ? allActiveOrdersChildren : selectedProject.getAllChildren();
    }

    @Override
    public void setSelectedProject(Order selectedProject) {
        this.selectedProject = selectedProject;
    }

    @Override
    public Order getSelectedProject() {
        return selectedProject;
    }

    @Override
    public Resource getResource() {
        return resource;
    }

    public void keepSortedExpenseSheetLines(ExpenseSheetLine expenseSheetLine, LocalDate newDate) {
        if (getExpenseSheet() != null)
            this.expenseSheet.keepSortedExpenseSheetLines(expenseSheetLine, newDate);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPersonalAndBelongsToCurrentUser(ExpenseSheet expenseSheet) {
        if (!expenseSheet.isPersonal())
            return false;


        SortedSet<ExpenseSheetLine> expenseSheetLines = getFromDB(expenseSheet).getExpenseSheetLines();
        Resource resource = expenseSheetLines.iterator().next().getResource();

        User user = UserUtil.getUserFromSession();

        return user.getWorker().getId().equals(resource.getId());
    }

}