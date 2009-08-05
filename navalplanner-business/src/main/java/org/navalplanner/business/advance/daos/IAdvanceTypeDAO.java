package org.navalplanner.business.advance.daos;

import org.navalplanner.business.advance.entities.AdvanceType;
import org.navalplanner.business.common.daos.IGenericDAO;

/**
 * Contract for {@link AdvanceTypeDao}
 * @author Susana Montes Pedreira <smontes@wirelessgalicia.com>
 */

public interface IAdvanceTypeDAO extends IGenericDAO<AdvanceType, Long>{
    public boolean existsNameAdvanceType(String unitName);

    public AdvanceType findByName(String name);

}